package cn.net.zhijian.mesh.frm.config.tokenchecker;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsTokenChecker;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.UrlPathInfo;

/**
 * 通过oAuth来校验调用方token是否有效。
 * 被调方在调用此函数验证token是否合法时，caller不是自己，而是发起调用的服务名，callee就是本方
 * 如果搞错了，调用将会混乱，getAccessToken与verifyToken不在一个服务中执行。
 * OAUTH只适用于服务间互调，webdb当做特殊的服务，其caller名称为db，而不是webdb。
 * 调用方使用自己的私钥签名生成AppToken访问OAUTH服务，获得一个OAuthToken；
 * 对被调发起请求时，携带OAuthToken，被调方携带此token向OAUTH校验是否正常。
 * OAUTH验证，使用的codebook验证，且需要调用方必须在被调方的callers白名单中。
 * OAUTH验证要比APP验证复杂。
 * <p>
 * 普通服务间调用，只有三种验证方法，所以校验方式也有三种：
 * 1）请求是来自其他服务，使用OAUTH_CHECKER，调用oAuth.verify接口；
 * 2）请求来自本服务的其他节点，使用APP_KEY_CHECKER，被调方使用调用方的公钥校验；
 * 3）请求是来自运维平台，使用OM_KEY_CHECKER，通过运维平台的公钥校验，是APP的特里；
 *
 * @author flyinmind of csdn.net
 *
 */
class OAUTH extends AbsTokenChecker {
    private static final Logger LOG = LogUtil.getInstance();
    
    OAUTH() {
        super(OAUTH_TOKEN_CHECKER);
    }

    @Override
    public CompletableFuture<AccessToken> check(AbsServerRequest req, String token) {
        long id = AccessToken.hashCode(token);
        AccessToken cacheToken = CachedTokens.get(id);
        if(cacheToken != null && !cacheToken.expired()) {
            if(!cacheToken.needUpdate()) {
                if(token.equals(cacheToken.generate())) {
                    return CompletableFuture.completedFuture(cacheToken);
                }
            }
        }

        ServiceInfo si = req.serviceInfo();
        if(LOG.isDebugEnabled()) {
            String caller = AccessToken.getCaller(token);
            LOG.debug("OAUTH_CHECKER:{}->{},traceId:{}", caller, si.name, req.traceId);
        }

        if(!PartitionConfig.instance().isPartitionValid(token)) { //除了公共分区，其他分区之间不能互通
            LOG.warn("Service request from invalid partition,!={})", PartitionConfig.instance().partition);
            return CompletableFuture.completedFuture(null);
        }

        /*
         * 向oAuth服务发起verify请求。
         * 因为CompletableFuture不能像Future那样有run函数，一旦创建就会运行，
         * 所以只能用一个lock来实现阻止相同请求的功能
         */
        UrlPathInfo url = new UrlPathInfo("verify").appendPara(SEG_ACCESS_TOKEN, token, false);
        ServiceReqBuilder builder = new ServiceReqBuilder(si, SERVICE_OAUTH2)
                .url(url.toString()).traceId(req.traceId).cid(req.cid());
        return ServiceClient.getPublic(builder).thenApplyAsync((hr)-> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to call {}, result:{}", SERVICE_OAUTH2, hr.brief());
                return null;
            }

            //在oauth中验签，不需要本地验签
            AccessToken at = AccessToken.parse(token, ITokenWorker.NullTokenWorker);
            if(at == null || at.expired()) {
                LOG.error("Invalid token {}", token);
                return null;
            }

            int tokenType = at.tokenType();
            //被调服务与当前服务名称不符，如果是TOKENTYPE_DB，不能做这样的判断，因为DB的callee是db名称
            if(tokenType == TOKENTYPE_SERVICE) {
                if(!at.callee.equals(si.name)) {
                    String caller = AccessToken.getCaller(token);
                    LOG.warn("Invalid callee {}, not equals to {}, from {}",
                            at.callee, si.name, caller);
                    return null;
                }
            } else if(tokenType != TOKENTYPE_DB){
                LOG.warn("Invalid token type {},must be {}",
                        TOKENTYPE_NAMES[tokenType], TOKENTYPE_NAMES[TOKENTYPE_SERVICE]);
                return null;
            }

            CachedTokens.put(id, at);
            return at;
        }, Pool);
    }
}
