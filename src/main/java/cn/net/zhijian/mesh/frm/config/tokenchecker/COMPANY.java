package cn.net.zhijian.mesh.frm.config.tokenchecker;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsTokenChecker;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;

/**
 * 通过调用/company/verify实现token校验，如果是公有云个人用户token，COMPANY自动转为USER。
 * 私有云中用私钥签名(公司注册或登录时，在公有云中保存了相应的公钥）。
 * 此特性用于解决部分服务在私网，部分服务在公网的使用场景。
 * 如果缓存中有token，则直接校验缓存中的，如果没有，则到company服务校验，
 * 成功后，记入缓存，以备后用。
 * 
 * @author flyinmind of csdn.net
 *
 */
public class COMPANY extends AbsTokenChecker {
    private static final Logger LOG = LogUtil.getInstance();

    COMPANY() {
        super(COMPANY_TOKEN_CHECKER);
    }
    
    COMPANY(String name) {
        super(name);
    }

    @Override
    public CompletableFuture<AccessToken> check(AbsServerRequest req, String token) {
        //caller都是1(admin)，callee是被调服务名
        //如果codebook签名，请求发到本地集群，公司私钥签名，请求发向根服务器
        //所以用cid、stakeholder计算cacheId，不会导致重叠
        int cid = req.cid();
        long cacheId = StringUtil.longHashCode(cid, AccessToken.getStakeholder(token));
        //long cacheId = AccessToken.hashCode(token);
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
            LOG.debug("{}:{}->{},traceId:{},token-head:{}",
                name, caller, si.name, req.traceId, token.substring(0, AccessToken.FIXED_HEAD_LEN));
        }

        PartitionConfig partCfg = PartitionConfig.instance();
        if(!partCfg.isPartitionValid(token)) {
            LOG.warn("Service request from invalid partition,!={})", partCfg.partition);
            return CompletableFuture.completedFuture(null);
        }

        int tokenType = AccessToken.getTokenType(token);
        if(tokenType != TOKENTYPE_USER) {
            String name = tokenType < TOKENTYPE_NAMES.length ? TOKENTYPE_NAMES[tokenType] : "UnknownTokenType("+tokenType+')';
            LOG.warn("Invalid token type {},must be {}", name, TOKENTYPE_NAMES[TOKENTYPE_USER]);
            return CompletableFuture.completedFuture(null);
        }
        
        int signType = AccessToken.getSignType(token);
        //如果是codebook签名，则直接通过user鉴权
        //当公司服务部署在公有云，或者公有云个人用户访问company认证接口时用到
        if(signType == SIGNTYPE_CODEBOOK) {
            return BASEUSER.verify(req, SERVICE_USER, token, cid, cacheId);
        }

        if(signType != SIGNTYPE_COMPANYKEY) {
            String name = signType < SIGNTYPE_NAMES.length ? SIGNTYPE_NAMES[signType] : "UnknownSignType("+signType+')';
            LOG.warn("Invalid sign type {},must be {}", name, SIGNTYPE_NAMES[SIGNTYPE_COMPANYKEY]);
            return CompletableFuture.completedFuture(null);
        }

        //在私有云中，admin访问自己公司的管理接口时，使用本地公司公钥验签
        //比如ServiceTool中的安装、卸载等操作
        CompanyInfo ci = CompanyInfo.instance();
        if(ci.id == cid) { //只要本地有，则使用本地的验证
            ITokenWorker tw = ci.tokenWorker();
            AccessToken at = AccessToken.parse(token, tw);
            //用户ID必须是ADMIN_UID，CID必须与本地设置的一致
            if(at != null) {
                if(!at.expired() && at.caller.equals(IOAuth.ADMIN_UID)) {
                    int pos = at.ext.indexOf(AccessToken.EXT_SEPARATOR);
                    int tokenCid = Integer.parseInt(at.ext.substring(pos + 1));
                    if(tokenCid == cid) {
                        return CompletableFuture.completedFuture(at);
                    }
                }
                LOG.warn("Invalid local company token,uid:{},signtype:{}", at.caller, SIGNTYPE_NAMES[signType]);
            } else {
                LOG.warn("Invalid local company token,signtype:{}", SIGNTYPE_NAMES[signType]);
            }
            return CompletableFuture.completedFuture(null);
        } else if(partCfg.isPrivate()) { //私有环境，必须存在公司配置信息
            LOG.warn("Invalid local company:{}", cid);
            return CompletableFuture.completedFuture(null);
        }
        
        //向company服务发起verify请求
        //请求是发到集群自身的，如果是公司环境，company服务是内置服务
        //如果是根环境中，有独立的company服务
        //所以无论是公司环境还是根环境中，都可以查到company服务
        UrlPathInfo url = new UrlPathInfo("verify").appendPara(SEG_ACCESS_TOKEN, token, false);
        ServiceReqBuilder builder = new ServiceReqBuilder(si, SERVICE_COMPANY)
                .url(url.toString())
                .traceId(req.traceId)
                .cid(cid);
        return ServiceClient.getPublic(builder).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to call {}, result:{}", SERVICE_COMPANY, hr.brief());
                return null;
            }

            //在user中验签，本地不需要验签，只解析
            AccessToken at = AccessToken.parse(token, ITokenWorker.NullTokenWorker);
            if(at == null || at.expired()) {
                LOG.error("Invalid token {}", token);
                return null;
            }

            if(!si.name.equals(at.callee)) {
                LOG.warn("Invalid callee {},should be {}", at.callee, si.name);
                return null;
            }

            CachedTokens.put(cacheId, at);
            return at;
        }, Pool);
    }
    
    @Override
    public boolean isUserToken() {
        return true;
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
        } else {
            params.put(IConst.EMBEDED_TOKEN_ACC, at.ext);
        }
    }
}
