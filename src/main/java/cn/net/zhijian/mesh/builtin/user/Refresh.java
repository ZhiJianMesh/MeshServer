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
 * grant_type=refresh_token&refresh_token=tGzv3JOkF0XG5Qx2TlKWIA
 * 刷新user token，其他服务token会跟着过期
 * @author flyinmind of csdn.net
 *
 */
public final class Refresh extends UserBase {
    private static final Logger LOG = LogUtil.getInstance();
    
    public Refresh(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
        apiInfo.addExtPara(PARA_UID);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        return req.getDbNo().thenComposeAsync(dbNo -> {
            if(dbNo < 0) {
                return futureResult(RetCode.NO_RIGHT, "db not ready");
            }
            return refreshToken(req, resp, req.cid(), dbNo);
        }, Pool);
    }
    
    private CompletableFuture<HandleResult> refreshToken(AbsServerRequest req, Map<String, Object> resp, int cid, int dbNo) {
        AccessToken at = req.token();
        String reqToken = at.generate();
        long cacheId = getCacheId(req.cid(), reqToken);
        String userId = at.caller.replace("'", "''");//替换掉单引号，防止sql注入
        AccessToken cacheUserToken = UserTokenCache.get(cacheId);
        CompletableFuture<Long> cf;
        
        if(cacheUserToken != null && !cacheUserToken.expired()) {//缓存未过期，不必从数据库取
            if(!reqToken.equals(cacheUserToken.generate())) {
                return futureResult(RetCode.NO_RIGHT, "invalid token");
            }
            cf = CompletableFuture.completedFuture(cacheUserToken.expiresAt());
        } else {
            req.put(PARA_UID, userId);
            //refresh token不支持company token
            cf = super.handle(req, resp).thenApplyAsync(hr -> {
                if(hr.code != RetCode.OK || hr.data == null) {
                    LOG.error("Fail to get token info of {}", userId);
                    return -1L;
                }
                long hashInDb = ValParser.getAsLong(hr.data, PARA_TOKENHASH);
                long reqHash = StringUtil.longHashCode(reqToken);
                if(hashInDb != reqHash) {
                    LOG.error("Invalid request hash of user {}", userId);
                    return -1L;
                }
                return ValParser.getAsLong(hr.data, RESP_EXPIRESAT);
            }, Pool);
        }

        return cf.thenComposeAsync(exp -> {
            long cur = req.reqTime;
            if(exp < cur) {
                return futureResult(RetCode.NO_RIGHT, "token expired");
            }
            return getTokenWorker(cid, req.serviceInfo(), false).thenComposeAsync(tw -> {
                //重新生成token
                AccessToken newToken = tw.create(PartitionConfig.instance().partition, userId,
                        SERVICE_USER, cur + USER_TOKEN_EXPIRES_IN, at.ext, TOKENTYPE_USER);
                String tokenStr = newToken.generate();
                if(StringUtil.isEmpty(tokenStr)) {
                    LOG.error("Fail to generate token for {}", userId);
                    return futureResult(RetCode.NO_RIGHT, "fail to generate");
                }
                resp.put(SEG_ACCESS_TOKEN, tokenStr);
                resp.put(SEG_REFRESH_TOKEN, StringUtil.base64UUID());
                resp.put(SEG_EXPIRES_AT, newToken.expiresAt());//token对超时时间做了调整，与输入值不一定相同
                resp.put(SEG_TOKEN_TYPE, "session");
                resp.put("id", userId);

                //更新缓存中的token，gw需要按tokenCaller分发，否则不能及时更新
                UserTokenCache.put(cacheId, newToken);
                return futureResult();
            }, Pool);
        }, Pool);
    }
}