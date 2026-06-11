package cn.net.zhijian.mesh.client;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.DeferrableEle;
import cn.net.zhijian.mesh.bean.ExpirableEle;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 访问bios服务的客户端，所有的请求都发给主bios，
 * 主bios默认为第一个bios，当主bios故障时，切换到下一个，以此类推
 * 当所有都故障时，回到第一个，此时如果还失败，只能最终失败。
 * config部分只能存储服务的公共配置，不区分公司，
 * 如果需要区分公司，应存放到config服务中。
 * bioclient中的函数在调用时都可以不传cid，serviceclient中函数都必须传递正确的cid
 * @author flyinmind of csdn.net
 *
 */
public class BiosClient extends HttpClient implements IOAuth {
    private static final Logger LOG = LogUtil.getInstance();
    
    private static final int CONFIG_EXPIRE_TIME = 300; //seconds
    private static final Map<Long, TokenValue> AppTokenCache = new ConcurrentHashMap<>();

    /**
     * 在bios中存放服务级的配置项，在目录'/service/@{serviceName}/configs'下。
     * 配置项只能通过om修改，缓存会定期更新，修改后不会立刻生效
     */
    private static final Map<Integer, ConfigValue> ConfigCache = new ConcurrentHashMap<>();

    /**
     * 指定节点，访问bios，需要使用应用自己的私钥产生token，
     * bios中保存了所有应用的公私钥，bios使用对应的公钥验证token合法性
     * @param node 指定的节点
     * @param req 请求体
     * @return 异步执行结果
     */
    public static CompletableFuture<HandleResult> get(NodeAddress node, ServiceReqBuilder req) {
        String url = serviceApiUrl(SCHEME, SERVICE_BIOS, req.url, node.addr);
        return getFrom(node, url, req.headers, req.traceId);
    }
    
    /**
     * 访问bios.get接口，需要使用应用自己的私钥产生token，
     * bios中保存了所有应用的公私钥，bios使用对应的公钥验证token合法性
     * @param req 请求体
     * @return 异步执行结果
     */
    public static CompletableFuture<HandleResult> get(ServiceReqBuilder req) {
        NodeAddress node = PartitionConfig.instance().mainBios();
        String url = serviceApiUrl(SCHEME, SERVICE_BIOS, req.url, node.addr);
        return getFrom(node, url, req.headers, req.traceId);
    }

    /**
     * 访问bios.delete接口，需要使用应用自己的私钥产生token，
     * bios中保存了所有应用的公私钥，bios使用对应的公钥验证token合法性
     * @param req 请求体
     * @return 异步执行结果
     */
    public static CompletableFuture<HandleResult> delete(ServiceReqBuilder req) {
        NodeAddress node = PartitionConfig.instance().mainBios();
        String url = serviceApiUrl(SCHEME, SERVICE_BIOS, req.url, node.addr);
        return delFrom(node, url, req.headers, req.traceId);
    }
    
    /**
     * 访问bios.post接口
     * @param req 请求
     * @return 异步响应
     */
    public static CompletableFuture<HandleResult> post(ServiceReqBuilder req) {
        NodeAddress node = PartitionConfig.instance().mainBios();
        String url = serviceApiUrl(SCHEME, SERVICE_BIOS, req.url, node.addr);
        return postJsonTo(node, url, req.headers, req.body(), req.traceId);
    }
    
    /**
     * 访问bios.put接口
     * @param req 请求
     * @return 异步响应
     */
    public static CompletableFuture<HandleResult> put(ServiceReqBuilder req) {
        NodeAddress node = PartitionConfig.instance().mainBios();
        String url = serviceApiUrl(SCHEME, SERVICE_BIOS, req.url, node.addr);
        return putJsonTo(node, url, req.headers, req.body(), req.traceId);
    }
    
    /**
     * 使用本应用的私钥签名，在被调应用中使用对应公钥验签。
     * 只有以下三种情况使用：
     * 1）同一服务不同节点间互调；
     * 2）所有服务调用bios(bios中记录了所有应用的公私钥)；
     * 3）所有服务调用oAuth(oAuth从bios中获取应用公钥及features)。
     * @param caller 调用方信息
     * @param callee 被调用方名称
     * @param features 需要使用哪些特性，bios服务中的接口都没有限制feature
     * @param expires 有效期，单位秒
     * @return 服务token
     */
    public static String appToken(ServiceInfo caller, String callee, String features, int expires) {
        long tokenId = StringUtil.longHashCode(caller.name, SERVICE_SEPERATOR_S, callee);
        /*
         * 1）因为用私钥产生签名是一个耗时的操作，所以缓存；
         * 2）没有产生永久不过期的token，是因为防止token泄露后，无法过期；
         * 3）只要caller、callee相同，所有cid可以用一个token，cid不参与签名，替换cid即可。
         */
        TokenValue tv = AppTokenCache.get(tokenId);
        if(tv != null && tv.valid()) {
            return tv.token;
        }

        long expiresAt = expires <= 0 ? 0L : (System.currentTimeMillis() + 1000L * expires);
        AccessToken at = caller.tokenWorker.create(PartitionConfig.instance().partition,
                caller.name, callee, expiresAt, features, TOKENTYPE_SERVICE);
        String token = at.generate(); //用自己的私钥签名
        AppTokenCache.put(tokenId, new TokenValue(expires, token));

        return token;
    }

    public static String appToken(ServiceInfo caller, String callee, String features) {
        return appToken(caller, callee, features, 0);
    }
    //-------------------------------------------------------------------------
    /**
     * 获得配置参数，默认10分钟从config服务刷新一次，其他情况都尽量使用缓存
     * 此配置项不区分公司，在一个服务中唯一
     * @param caller 调用方
     * @param item 配置项
     * @param useCache 是否使用缓存
     * @return 配置项内容
     */
    public static CompletableFuture<String> getConfig(ServiceInfo caller, String item, boolean useCache) {
        int cacheId = StringUtil.concatHashCode(caller.name, "-", item);
        ConfigValue v0 = ConfigCache.get(cacheId);
        ConfigValue value;

        if(v0 == null) {
            v0 = new ConfigValue(600);
            //返回老的数据，原来不存在则返回null
            ConfigValue v1 = ConfigCache.putIfAbsent(cacheId, v0);
            value = v1 != null ? v1 : v0;
        } else {
            value = v0;
        }

        if(useCache && value.valid() && !value.needUpdate()) {
            return CompletableFuture.completedFuture(value.val);
        }

        synchronized(value) { //一个线程请求就可以了，其他线程等待
            if(value.valid() && !value.needUpdate()) {
                return CompletableFuture.completedFuture(value.val);
            }

            ServiceReqBuilder req = new ServiceReqBuilder(caller, SERVICE_BIOS)
                    .url("/config/get").appendPara("k", item)
                    .appToken("*")
                    .cid(CompanyInfo.instance().id) //不区分公司
                    .traceId(caller.name + '_' + item);
            return get(req).thenApplyAsync(hr -> {
                if(hr.code != RetCode.OK || hr.data == null) {
                    LOG.warn("Fail to get config {}.{}, result:{}",
                        caller.name, item, hr.brief());
                    return value.val;
                }
                String s = ValParser.getAsStr(hr.data, item);
                if(!StringUtil.isEmpty(s)) {
                    value.val = s; //直接改value中的成员，不必put到ConfigCache
                }
                return value.val;
            }, IThreadPool.Pool).whenCompleteAsync((v, e) -> {
                if(e != null) {
                    LOG.error("Fail to get config {}.{}", caller.name, item, e);
                }
                value.resetUpdate();
            }, IThreadPool.Pool);
        }
    }

    public static CompletableFuture<String> getConfig(ServiceInfo caller, String item) {
        return getConfig(caller, item, true);
    }

    /**
     * 创建配置项
     * @param caller 调用方信息
     * @param item 配置项名称
     * @param val 配置项值
     * @return 保存结果
     */
    public static CompletableFuture<Boolean> putConfig(ServiceInfo caller, String item, String val) {
        ServiceReqBuilder req = new ServiceReqBuilder(caller, SERVICE_BIOS)
                .traceId(caller.name + '_' + item)
                .url("/config/put")
                .put("k", item)
                .put("v", val)
                .appToken("*");
        return put(req).thenApplyAsync(hr -> {
            if(hr.code == RetCode.OK) {
                int cacheId = StringUtil.concatHashCode(caller.name, "-", item);
                ConfigCache.put(cacheId, new ConfigValue(CONFIG_EXPIRE_TIME, val));
                return true;
            }
            
            LOG.error("Fail to call putConfig({}), result:{}", req, hr.brief());
            return false;
        }, IThreadPool.Pool);
    }

    /**
     * 创建配置项，如果不存在则创建，如果存在，则放弃
     * @param caller 调用方信息
     * @param item 配置项名称
     * @param val 配置项值
     * @return 保存结果
     */
    public static CompletableFuture<String> putConfigIfAbsent(ServiceInfo caller, String item, String val) {
        ServiceReqBuilder req = new ServiceReqBuilder(caller, SERVICE_BIOS)
                .traceId(caller.name + '_' + item)
                .url("/config/putIfAbsent")
                .put("k", item)
                .put("v", val)
                .appToken("*");
        return put(req).thenApplyAsync(hr -> {
            if(hr.code == RetCode.OK) {
                String v = ValParser.getAsStr(hr.data, item);
                int cacheId = StringUtil.concatHashCode(caller.name, "-", item);
                ConfigCache.put(cacheId, new ConfigValue(CONFIG_EXPIRE_TIME, v));
                return v;
            }
            LOG.error("Fail to call putConfigIfAbsent({}), result:{}", req, hr.brief());
            return null;
        }, IThreadPool.Pool);
    }
    
    /**
     * 使用om密码或公司密码向指定的bios请求一些机密信息，比如company.cfg
     * 因为om的get操作不一定访问主实例，所以需要指定节点；而put/post/delete操作一定访问主实例
     * @param node 指定节点
     * @param si 所在服务
     * @param urlInfo url
     * @param pwd om密码或公司密码
     * @return 异步结果
     */
    public static CompletableFuture<HandleResult> omGet(NodeAddress node, ServiceInfo si, UrlPathInfo urlInfo, String pwd) {
        CompanyInfo ci = CompanyInfo.instance();

        ServiceReqBuilder req = new ServiceReqBuilder(si, SERVICE_BIOS)
                .url(urlInfo.toString())
                .traceId(si.name + "_get_" + ci.id)
                .cid(ci.id)
                .token(pwd);

        //向主bios实例请求，从bios实例可能并未启动
        return BiosClient.get(node, req);
    }
    
    public static CompletableFuture<HandleResult> omGet(ServiceInfo si, UrlPathInfo urlInfo, String pwd) {
        NodeAddress node = PartitionConfig.instance().mainBios();
        //向主bios实例请求，从bios实例可能并未启动
        return BiosClient.omGet(node, si, urlInfo, pwd);
    }
    //-------------------------------------------------------------------------
    /**
     * 用根密钥加密数据，用于加密user、oauth的codebooks，以及公司的密钥对。
     * 当此密钥丢失时会自动重建，同时codebooks会重新生成，
     * 但是公司数据根密钥对都解不开，需要重新登录后存入，如果此时公司服务器也故障，则加密数据将无法解开。
     * @param caller 调用方信息
     * @param plain 待加密的数据
     * @return 加密结果，在data.cipher中
     */
    public static CompletableFuture<HandleResult> rootEncode(ServiceInfo caller, String plain) {
        return post(new ServiceReqBuilder(caller, SERVICE_BIOS)
                .url("/rootkey/encode")
                .cid(CompanyInfo.instance().id)
                .traceId(caller.name)
                .put(PLAIN, plain));
    }

    /**
     * 用根密钥解密数据
     * @param caller 调用方信息
     * @param cipher 待解密的数据
     * @return 解密结果，在data.plain中
     */
    public static CompletableFuture<HandleResult> rootDecode(ServiceInfo caller, String cipher) {
        return post(new ServiceReqBuilder(caller, SERVICE_BIOS)
                .url("/rootkey/decode")
                .cid(CompanyInfo.instance().id)
                .traceId(caller.name)
                .put(CIPHER, cipher));
    }
    
    public static String downloadCfgFile(String name, String omPwd) throws MeshException {
        NodeAddress node = PartitionConfig.instance().mainBios();
        UrlPathInfo urlInfo = new UrlPathInfo(IConst.SERVICE_URL_API).push("getCommon")
                .appendPara("name", name, true);

        try {
            ServiceInfo si = ServiceClient.backendService(); //在此之前需要初始化backen，防止servermain中
            HandleResult hr = BiosClient.omGet(node, si, urlInfo, omPwd).get(3, TimeUnit.SECONDS);
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to download cfg {} from {},result:{}", name, node.addr, hr.brief());
                return "";
            }
            return ValParser.getAsStr(hr.data, name);
        } catch (Exception e) {
            LOG.error("Fail to download cfg {} from {}", name, node.addr, e);
           throw new MeshException("fail to download " + name + " from " + node.addr);
        }
    }
    //-------------------------------------------------------------------------
    private static class ConfigValue extends DeferrableEle {
        public String val = null;

        public ConfigValue(int expiresIn) {
            super(expiresIn);
        }

        public ConfigValue(int expiresIn, String val) {
            super(expiresIn);
            this.val = val;
        }

        public boolean valid() {
            return this.val != null;
        }
    }
    
    private static class TokenValue extends ExpirableEle {
        public final String token;

        public TokenValue(int expiresIn, String token) {
            super(expiresIn);
            this.token = token;
        }

        public boolean valid() {
            return this.token != null && !expired();
        }
    }
}
