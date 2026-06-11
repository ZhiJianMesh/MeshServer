package cn.net.zhijian.mesh.frm.config.tokenchecker;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsTokenChecker;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * 通过调用/user/verify实现token校验。
 * 如果缓存中有token，则直接校验缓存中的，如果没有，则到user服务校验，成功后，会记入缓存，以备后用。
 * user与uniuser的验证是类似的，只是服务名不同。
 * uniuser只在公有云部署，提供个人帐号认证
 *
 * @author flyinmind of csdn.net
 *
 */
class BASEUSER extends AbsTokenChecker implements IThreadPool, IOAuth {
    private static final Logger LOG = LogUtil.getInstance();
    private final String userService;

    BASEUSER(String name, String userService) {
        super(name);
        this.userService = userService;
    }

    @Override
    public CompletableFuture<AccessToken> check(AbsServerRequest req, String token) {
        //同一用户可能有多个cid，所以不能只以stakeholder计算cacheId，同时要加上cid
        //如果用全部token计算cacheId，在token过期或重新登录时，不能及时从缓存中删除，导致累积
        int cid = req.cid();
        long cacheId = StringUtil.longHashCode(cid, AccessToken.getStakeholder(token));
        AccessToken cacheToken = CachedTokens.get(cacheId);
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
            LOG.debug("{}:{}->{},traceId:{}", name, caller, si.name, req.traceId);
        }

        if(!PartitionConfig.instance().isPartitionValid(token)) {
            LOG.warn("Service request from invalid partition,!={})", PartitionConfig.instance().partition);
            return CompletableFuture.completedFuture(null);
        }

        int signType = AccessToken.getSignType(token);
        if(signType != SIGNTYPE_CODEBOOK) {
            String name = signType < SIGNTYPE_NAMES.length ? SIGNTYPE_NAMES[signType] : "UnknownSignType("+signType+')';
            LOG.warn("Invalid sign type {},must be {}", name, SIGNTYPE_NAMES[SIGNTYPE_CODEBOOK]);
            return CompletableFuture.completedFuture(null);
        }

        int tokenType = AccessToken.getTokenType(token);
        if(tokenType != TOKENTYPE_USER) {
            String name = tokenType < TOKENTYPE_NAMES.length ? TOKENTYPE_NAMES[tokenType] : "UnknownTokenType("+tokenType+')';
            LOG.warn("Invalid token type {},must be {}", name, TOKENTYPE_NAMES[TOKENTYPE_USER]);
            return CompletableFuture.completedFuture(null);
        }
        
        return verify(req, userService, token, cid, cacheId);
    }
    
    @Override
    public void addAvailables(ApiInfo ai) {
        super.addAvailables(ai);
        ai.addExtPara(IConst.EMBEDED_TOKEN_CID);
        ai.addExtPara(IConst.EMBEDED_TOKEN_ACC);
    }

    @Override
    public void addParas(Map<String, Object> params, AccessToken at) {
        super.addParas(params, at);
        int pos = at.ext.indexOf(AccessToken.EXT_SEPARATOR); //account,cid
        if(pos > 0) {
            params.put(IConst.EMBEDED_TOKEN_ACC, at.ext.substring(0, pos));
            params.put(IConst.EMBEDED_TOKEN_CID, at.ext.substring(pos + 1));
        }
    }
    
    @Override
    public boolean isUserToken() {
        return true;
    }
    
    static CompletableFuture<AccessToken> verify(AbsServerRequest req, String userService, String token, int cid, long cacheId) {
        ServiceInfo si = req.serviceInfo();
        //向User服务发起verify请求
        ServiceReqBuilder builder = new ServiceReqBuilder(si, userService)
                .url("/verify?" + SEG_ACCESS_TOKEN + '=' + token)
                .traceId(req.traceId)
                .cid(cid);
        return ServiceClient.getPublic(builder).thenApplyAsync((hr)-> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to call {},result:{},cid:{}", IConst.SERVICE_USER, hr.brief(), cid);
                return null;
            }

            //在user或boot中验签，本地不需要验签，只解析
            AccessToken at = AccessToken.parse(token, ITokenWorker.NullTokenWorker);
            if(at == null || at.expired()) {
                LOG.error("Invalid token {}", token);
                return null;
            }

            //服务token只能调用服务本身，需要通过user的serviceToken接口转换。
            if(!si.name.equals(at.callee)) {
                LOG.warn("Invalid callee {},should be {}", at.callee, si.name);
                return null;
            }

            CachedTokens.put(cacheId, at);
            return at;
        }, Pool);
    }
}
