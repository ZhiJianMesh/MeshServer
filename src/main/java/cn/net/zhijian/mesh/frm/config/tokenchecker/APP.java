package cn.net.zhijian.mesh.frm.config.tokenchecker;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsTokenChecker;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.mesh.frm.tokenworker.EccTokenWorker;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.FifoCache;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 用服务的私钥签名，被调服务中使用调用方服务的公钥验证，
 * 此类验证，不需要在服务的callers中申明。
 * @author flyinmind of csdn.net
 *
 */
class APP extends AbsTokenChecker {
    private static final Logger LOG = LogUtil.getInstance();
    protected static final FifoCache<String, ITokenWorker> tokenWorkers = new FifoCache<>(
            3600/*秒，最大缓存时间*/, 5000/*最大缓存数量*/);
    // 所有服务都能调用，都在一套bios管理下的环境中，这样的设置相当于是内部public
    // 但是不能bios管理的环境，则不能调用
    public static final String SERVICE_ANY = "*";
    
    //指定调用方，使用这种方式，可以不在bios中申明依赖服务
    protected final String services;

    APP(String name, String services) { //指定调用方，可以支持多个服务，用逗号分隔
        super(name);
        this.services = services;
    }
    
    APP(String services) { //指定调用方，可以支持多个服务，用逗号分隔
        super(APP_TOKEN_CHECKER);
        this.services = services;
    }

    APP() { //只容许自己调用自己
        this(null);
    }

    @Override
    public CompletableFuture<AccessToken> check(AbsServerRequest req, String token) {
        if (this.services == null) { //只容许自己调用自己
            return check(req, token, req.serviceInfo().tokenWorker);
        }

        String caller = AccessToken.getCaller(token);
        if(StringUtil.isEmpty(caller)) {
            LOG.error("Invalid caller, it's empty");
            return CompletableFuture.completedFuture(null);
        }
        
        if(req.serviceInfo().name.equals(caller)) { //设置了服务列表，但是自己调用自己 
            return check(req, token, req.serviceInfo().tokenWorker);
        }

        if(!SERVICE_ANY.equals(this.services) //不容许“任何服务”调用
           && !this.services.contains(caller)) { //也不容许指定的服务调用
            LOG.error("Invalid caller {}, it must be in [{}]", caller, this.services);
            return CompletableFuture.completedFuture(null);
        }
        return check(req, token, caller);
    }
     
    protected CompletableFuture<AccessToken> check(AbsServerRequest req, String token, String caller) {
        ITokenWorker tokenWorker = tokenWorkers.get(caller);
        if(tokenWorker != null) {
            return check(req, token, tokenWorker);
        }

        ServiceInfo si = req.serviceInfo();
        /*
         * 向bios服务查询被调服务的public key，此接口是公口接口。
         * 配置时，tokenChecker:"APP-service_name"
         */
        UrlPathInfo url = new UrlPathInfo("/service/getPubKey")
                .appendPara("service", caller, false);
        ServiceReqBuilder builder = new ServiceReqBuilder(si, SERVICE_BIOS)
                .url(url.toString())
                .cid(LOCAL_COMPANY_ID)
                .traceId(req.traceId);
        return BiosClient.get(builder).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK || hr.data == null) {
                LOG.error("Fail to get public key of {} from {}, result:{}", caller, SERVICE_BIOS, hr.brief());
                return CompletableFuture.completedFuture(null);
            }

            String sPubKey = ValParser.getAsStr(hr.data, "publicKey");
            try {
                EccKeyPair kp = EccKeyPair.parse(sPubKey);
                int signType = SERVICE_OM.equals(caller) ? SIGNTYPE_OMKEY : SIGNTYPE_APPKEY;
                ITokenWorker tw = new EccTokenWorker(kp.ver, null, kp.pub, signType);
                tokenWorkers.put(caller, tw); //只可用于鉴权，因为只有公钥，没有私钥
                return check(req, token, tw);
            } catch (Exception e) {
                return CompletableFuture.completedFuture(null);
            }
        }, Pool);
    }

    //用于junit测试中
    void setTokenWorker(String service, ITokenWorker tw) {
        tokenWorkers.put(service, tw);
    }
    
    //用于junit测试中
    ITokenWorker getTokenWorker(String service) {
        return tokenWorkers.get(service);
    }
    
    private CompletableFuture<AccessToken> check(AbsServerRequest req, String token, ITokenWorker tokenWorker) {
        long cacheId = AccessToken.hashCode(token);
        /*
         * 尽管调用方每次请求都可能更换token，仍然先读缓存，只要它没过期，仍然校验通过。
         * 因为公私钥计算很消耗CPU
         */
        AccessToken at = CachedTokens.get(cacheId);
        if(at != null && !at.expired()) {
            if(token.equals(at.generate())) {
                return CompletableFuture.completedFuture(at);
            }
        }

        ServiceInfo si = req.serviceInfo();
        if(LOG.isDebugEnabled()) {
            LOG.debug("{}:{}->{},traceId:{}", name,
                    AccessToken.getCaller(token), si.name, req.traceId);
        }

        if(!PartitionConfig.instance().isPartitionValid(token)) {
            LOG.warn("Service request from invalid partition,!={})", PartitionConfig.instance().partition);
            return CompletableFuture.completedFuture(null);
        }

        at = AccessToken.parse(token, tokenWorker);
        if(at == null || at.expired()) {
            LOG.warn("Fail to parse APP token in service {} from {}",
                    si.name, AccessToken.getCaller(token));
            return CompletableFuture.completedFuture(null);
        }
        
        if(!at.callee.equals(si.name)) {
            LOG.warn("Invalid callee {}, not equals to {}, from {}", at.callee, si.name, at.caller);
            return CompletableFuture.completedFuture(null);
        }

        CachedTokens.put(cacheId, at); //验证通过的才会放入缓存
        return CompletableFuture.completedFuture(at);
    }
    
    @Override
    public void addAvailables(ApiInfo ai) {
        super.addAvailables(ai);
        ai.addExtPara(IConst.EMBEDED_TOKEN_CID);
        ai.addExtPara(IConst.EMBEDED_TOKEN_FEATURE);
    }
    
    @Override
    public void addParas(Map<String, Object> params, AccessToken at) {
        super.addParas(params, at);
        int pos = at.ext.indexOf(AccessToken.EXT_SEPARATOR); //feature,cid
        if(pos > 0) {
            params.put(IConst.EMBEDED_TOKEN_FEATURE, at.ext.substring(0, pos));
            params.put(IConst.EMBEDED_TOKEN_CID, at.ext.substring(pos + 1));
        }
    }
}
