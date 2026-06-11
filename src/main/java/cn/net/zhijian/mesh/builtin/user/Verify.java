package cn.net.zhijian.mesh.builtin.user;

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
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 被最终用户调用的服务节点向user验证token是否有效
 * 请求只有一个参数access_token=xxx。
 * <p>
 * 暂时只支持单端的情况，如果支持多端，请求参数中需要携带端侧标识。
 * 同一帐号，只支持单端登录可以满足绝大部分的应用场景。
 * 用户服务级token(UST)verify时，只有用户token(UT)失效，则所有UST全部失效。
 * <p>
 * 在user服务中没有分库操作，但是在uniuser中支持使用用户id进行分库。
 * 首先从本地缓存中查询，如果有，则比对有效性，如果没有，则从数据库中查询。
 * 
 * @author flyinmind of csdn.net
 *
 */
public final class Verify extends UserBase {
    private static final Logger LOG = LogUtil.getInstance();

    public Verify(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
        apiInfo.addExtPara(PARA_UID);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String token = req.getString(SEG_ACCESS_TOKEN);
        PartitionConfig partCfg = PartitionConfig.instance();
        if(!partCfg.isPartitionValid(token)) {
            LOG.error("User request from a invalid partition,!={})", partCfg.partition);
            return CompletableFuture.completedFuture(new HandleResult(RetCode.NO_RIGHT, "invalid token"));
        }
        int cid = req.cid(); //req.token未初始化，cid只能从header中获取
        long cacheId = getCacheId(cid, token);
        AccessToken tokenInCache = UserTokenCache.get(cacheId);
        if(tokenInCache != null) {
            if(!tokenInCache.expired()) {
                if(token.equals(tokenInCache.generate())) {
                    return futureResult(); //历史上验签过，不必再验
                }
            } else {
                /*
                 * 与缓存中不一致，有可能是重新登录、或refreshtoken后，缓存没有及时刷新导致的，
                 * 所以出现这种情况时，需要从数据库中重新加载一次。
                 */
                UserTokenCache.remove(cacheId); //删除过期缓存token
            }
        }
        
        return verify(req, resp, cid, token, cacheId);
    }

    private CompletableFuture<HandleResult> verify(AbsServerRequest req, Map<String, Object> resp, int cid, String token, long cacheId) {
        /*
         * 用户认证中，只使用codebook。
         * 公司私钥签名的token，只会用于访问公有云服务，此操作在company中实现。
         * 用户token与user记录的token比较一下，一致才算真正有效，防止端侧持有已废弃但仍未过期的token。
         * 比如用户重新登录，原来的token仍然有效，但是这时不应该放行。
         * 限制：当用户重登陆后，必须verify过一次，才能刷新缓存中的token，否则原有token仍然有效。
         */

        return getTokenWorker(cid, serviceInfo, false).thenComposeAsync(tw -> {
            if(tw == null) {
                LOG.error("Fail to get token worker of company {}", cid);
                return futureResult(RetCode.NO_RIGHT, "invalid token");
            }
            AccessToken reqToken = AccessToken.parse(token, tw);
            if(reqToken == null || reqToken.expired()) { //先校验请求token
                LOG.error("Fail to parse token {}", AccessToken.getCallee(token));
                return futureResult(RetCode.NO_RIGHT, "invalid token");
            }
            req.put(PARA_UID, reqToken.caller);
            
            return super.handle(req, resp).thenApplyAsync(hr -> {
                if (hr.code != RetCode.OK) {
                    LOG.error("Fail to get token expiresAt of {}", reqToken.caller);
                    return new HandleResult(RetCode.NO_RIGHT, "invalid token");
                }

                long exp = ValParser.getAsLong(hr.data, RESP_EXPIRESAT);
                if(reqToken.expiresAt() != exp) { //重新登录过，老token必须全部失效
                    LOG.error("Invalid user token `{}`, it was relogined, expiresAt:{}!={}",
                            AccessToken.getCallee(token), exp, reqToken.expiresAt());
                    return new HandleResult(RetCode.NO_RIGHT, "invalid token");
                }

                if(reqToken.callee.equals(serviceInfo.name)) {
                    long tokenHashInDb = ValParser.getAsLong(hr.data, PARA_TOKENHASH);
                    long reqTokenHash = StringUtil.longHashCode(token);
                    if (reqTokenHash != tokenHashInDb) {
                        LOG.error("Invalid user token, token hash not equals");
                        return new HandleResult(RetCode.NO_RIGHT, "invalid token");
                    }
                }

                UserTokenCache.put(cacheId, reqToken);//db中的token，认为是可信的，不再验签
                return HandleResult.OK;
            }, Pool);
        }, Pool);
    }
}