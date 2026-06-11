package cn.net.zhijian.mesh.builtin.oauth2;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.LogUtil;

/**
 * oauth是一个特殊服务，在bios中不会出现。
 * 因为它本身用的数据库就是bios，任何服务，只要在bios中注册过，都可以调用它获得access_token。
 * 而用户token在user服务中实现申请与验证，与oAuth无关。
 * @author flyinmind of csdn.net
 */
public final class Auth extends AuthBase {
    private static final Logger LOG = LogUtil.getInstance();
    
    public Auth(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    /**
     * {
     *   "access_token":"caller @ ver + signType + tokenType + payloadlen + payload + sign",
     *   "token_type":"session",
     *   "expires_at":cur+3600,
     * }
     * @param req 从请前方传递来的参数
     * @param resp  前面的handle返回的参数，多个handle的返回累积到resp中。
     *              此参数不可修改
     * @return 执行结果
     */
    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String scope = req.getString("scope"); //callee
        String caller = req.getString("client_id"); //caller
        String secret = req.getString("client_secret"); //Ecc.sign(caller + '-' + callee)

        int tokenType;
        String callee;
        if(scope.startsWith(DB_CALLEE_HEAD)) { //请求数据库的token
            callee = scope.substring(DB_CALLEE_HEAD.length());
            tokenType = TOKENTYPE_DB;
        } else if(scope.startsWith(SERVICE_CALLEE_HEAD)) {
            callee = scope.substring(SERVICE_CALLEE_HEAD.length());
            tokenType = TOKENTYPE_SERVICE;
        } else {
            return futureResult(RetCode.NOT_SUPPORTED_FUNCTION);
        }

        /*
         * Auth服务可以使用公共分区的，也可以使用相同分区的，取决于私有分区中是否存在Auth。
         * 服务间调用，如果token被劫持，然后修改caller，会导致验证不过，
         * 因为在这里无法缓存信息，必然校验不过。
         * 如果是用户token，会导致越权，比如将自己的token中caller换成高权限用户的，
         * 所以，在用户token的payload中增加了caller与partition字段
         */
        return getAppCache(tokenType, caller, callee, req.traceId).thenComposeAsync((appAuthInfo)-> {
            if(appAuthInfo == null) { //找不到应用信息
                return futureResult(RetCode.NO_RIGHT);
            }
            String src = caller + SERVICE_SEPERATOR_C + scope;
            byte[] signSrc = src.getBytes(DEFAULT_CHARSET);
            byte[] sign = ByteUtil.base642bin(secret);
            if(!appAuthInfo.verify(signSrc, sign)) {
                LOG.error("{},fail to check client_secret with app public key for {}", req.traceId, src);
                return futureResult(RetCode.NO_RIGHT);
            }

            return buildResponse(appAuthInfo.features, caller, callee, tokenType);
        }, Pool);
    }
    
    /**
     * {
     *   "access_token":"ver+partition+caller@ver+signType+payloadlen + payload + sign",
     *   "token_type":"session",
     *   "expires_at":cur + 3600,
     * }
     * @param features 可以使用的特性
     * @param caller 调用方
     * @param callee 被调方
     * @param tokenType token类型db、user、app
     * @return 执行结果
     */
    private CompletableFuture<HandleResult> buildResponse(String features,
            String caller, String callee, int tokenType) {
        long expiresAt = System.currentTimeMillis() +  2 * 3600 * 1000; //秒，两小时
        AccessToken accessToken = codebookTokenWorker.create(PartitionConfig.instance().partition,
                caller, callee, expiresAt, features, tokenType);
        Map<String, Object> data = new HashMap<>();
        data.put(SEG_ACCESS_TOKEN, accessToken.generate());
        data.put(SEG_EXPIRES_AT, expiresAt);
        data.put(SEG_TOKEN_TYPE, "session");
        
        return futureResult(data);
    }
}