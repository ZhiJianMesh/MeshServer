package cn.net.zhijian.mesh.builtin.oauth2;

import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.DeferrableEle;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

abstract class AuthBase extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();

    //缓存的被调服务信息，只可用于服务间调用（包括数据库），用户认证信息不在此缓存
    private static final Map<Integer, AppAuthInfo> AppAuthInfoCache = new HashMap<>();
    protected static ITokenWorker codebookTokenWorker = null;
    
    public AuthBase(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    static void setTokenWoker(ITokenWorker tw) {
        AuthBase.codebookTokenWorker = tw;
    }
    
    @Override
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        return true;
    }

    public static void clearAppCache() {
        AppAuthInfoCache.clear();
    }

    protected CompletableFuture<AppAuthInfo> getAppCache(int tokenType, String caller, String callee, String traceId) {
        //不可算错此id，否则保留的publicKey对应不上，造成验证 时而正确时而错误
        int authInfoId = StringUtil.concatHashCode(ByteUtil.int2Hex(tokenType, false), caller, callee);
        AppAuthInfo info = AppAuthInfoCache.get(authInfoId);
        if(info != null) {
            /*
             * 定期提前更新，只会让一个线程执行
             * 不定期清理过期的应用信息，因为应用信息不多，且极少变更
             */
            if(!info.needUpdate()) {
                return CompletableFuture.completedFuture(info);
            }
        }
        
        String url;
        if(tokenType == TOKENTYPE_DB) { //请求数据库的token
            url = "/db/authInfo";
        } else if(tokenType == TOKENTYPE_SERVICE) {
            url = "/service/authInfo";
        } else {
            return CompletableFuture.completedFuture(null);
        }

        /*
         * 从bios中获取用于认证的必须信息
         * 包括publicKey(公钥)、nodes(节点)、features(可使用特性)。
         * 如果没有features，则表示不是一个合法的caller。
         *
         * bios不会走oAuth流程，bios访问自己的数据库，不访问其他服务，
         * 否则会死循环，因为一旦bios调用oAuth2，而oAuth2又要调用bios
         */
        ServiceInfo si = serviceInfo();
        ServiceReqBuilder req = new ServiceReqBuilder(si, SERVICE_BIOS)
            .url(url)
            .cid(LOCAL_COMPANY_ID)
            .appendPara("service", caller)
            .appendPara("callee", callee)
            .appToken("*")
            .traceId(traceId);

        return BiosClient.get(req).thenComposeAsync((hr) -> {
            if(hr.code != RetCode.OK || hr.data == null) {
                LOG.error("Fail to call {}, code:{}", url, hr.code);
                return CompletableFuture.completedFuture(null);
            }

            String features = "*"; //自己调用自己的情况，可以调用所有features
            if(!caller.equals(callee)) { //不是调用自己，必须在服务的callers下存在；调用db的情况，无此处理
                features = ValParser.getAsStr(hr.data, "features");
                if(StringUtil.isEmpty(features)) {
                    LOG.error("{}({}):no features when {} calls {},not a valid caller",
                            url, traceId, caller, callee);
                    return CompletableFuture.completedFuture(null);
                }
            }

            String publicKey = ValParser.getAsStr(hr.data, "publicKey");
            EccKeyPair kp;
            try {
                kp = EccKeyPair.parse(publicKey);
            } catch (Exception e) {
                LOG.error("Fail to decode app public key {}", publicKey, e);
                return CompletableFuture.completedFuture(null);
            }

            AppAuthInfo appAuthInfo = new AppAuthInfo(kp.ver, kp.pub, features);
            AppAuthInfoCache.put(authInfoId, appAuthInfo);
            if(LOG.isDebugEnabled()) {
                LOG.debug("Get auth ok, clientId:{}, scope:{}, features:{}, pubKey:{}, cacheId:{}",
                        caller, callee, features, publicKey, authInfoId);
            }
            return CompletableFuture.completedFuture(appAuthInfo);
        }, Pool).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                if(info != null) {
                    info.resetUpdate(); //下一个请求，需要再次更新
                }
            }
        }, Pool);
    }
    
    static class AppAuthInfo extends DeferrableEle {
        //调用方在被调服务中，可以使用哪些特性
        public final String features;
        public final Ecc ecc;
        public final PublicKey publicKey; //公钥，每个服务都有自己的一对密钥

        public AppAuthInfo(int ver, PublicKey publicKey, String features, int expiresIn) {
            super(expiresIn);
            this.features = features;
            this.ecc = Ecc.instance(ver);
            this.publicKey = publicKey;
        }

        public AppAuthInfo(int ver, PublicKey publicKey, String features) {
            this(ver, publicKey, features, DEFAULT_EXPIRES_IN);
        }
        
        public boolean verify(byte[] signSrc, byte[] sign) {
            return ecc.verify(signSrc, sign, publicKey);
        }
    }
}