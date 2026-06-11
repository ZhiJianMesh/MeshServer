package cn.net.zhijian.mesh.client;

import java.io.File;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManager;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.bean.ProxyResponse;
import cn.net.zhijian.mesh.frm.abs.AbsHttpCallback;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.httpcb.BigFileDownloadCallback;
import cn.net.zhijian.mesh.frm.httpcb.BinaryCallback;
import cn.net.zhijian.mesh.frm.httpcb.DownloadCallback;
import cn.net.zhijian.mesh.frm.httpcb.ProxyBinaryCallback;
import cn.net.zhijian.mesh.frm.httpcb.RestCallback;
import cn.net.zhijian.mesh.frm.httpcb.StringCallback;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.util.HttpUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.RootTruster;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.internal.tls.OkHostnameVerifier;

/**
 * 
 * 内部访问http的客户端，只用于访问服务端接口，
 * 可以使用http/https协议，如果是https，则必须提供可信的证书，
 * 因为此实现中，没有提供预置的根证书
 * @author flyinmind of csdn.net
 */
public class HttpClient {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String MESH_CLIENT_AGENT = "srv_" + IConst.ENGINEVERSION;
    private static final String URL_API = '/' + IConst.SERVICE_URL_API;
    //root.cer二进制内容的base64编码，root.cer是根证书的公钥
    //.\bin\command.bat filebase64 cer\root.cer
    private static final String ROOTCER =
        "m8O0E3ZW1G7e38G028W11q8PA2RnmeW18eYX8vSF4CW0mmLCO0Z56CGL4C03Fe5QffKQXv68IzsRq1o"
        + "G15p2maW13K516CX03vKCS0Z66CGL4i03J8tRlHdBwXMQgbMOkvYRbHdBZvMCL0p46CGL4e03Ce5QffK"
        + "QXvcJbHtGE138NqWCq0ZCm4JCo03CoSZMOyWCn83Dm43Cu4ZCm0ZCte5CS536mOX13K513mm3QXMQAbM"
        + "Ok1YKlz6TWCKGni0C9Om0LHW1J8mGE537meX13K51Bmm4ozsRqvYUebcQf5cRkuMPqvoOk5J5mCX13K5"
        + "1Am03QXMQAbMOkvKPqDaJma5CJOm1gO8IEtZ01O02gO8IEtp01Sm02101ytWiE-lpWBFR2VL_UhDCO2i"
        + "Xx31LgTI1H_4ArqO56nU_UU2hlh6Lcq1hZNUEUJtIdLgtcRdqi5Nyc7Mw_NOEmFA8mu1CEOm0Lrn314m"
        + "_4Gm02801mmW13KL7JGG1mCG01yFCAO02gO8IEt3138m0810C5908s1S_9UPgiiEUw0m0l-I8GRH-Btf"
        + "1btpSclUjbpKc6kZ0X00puSg2qbmStFuhEiBcfOjBiYMCI34WbYvxHWb5ZhcwI2";

    /**
     * 此http客户端没有设置私有根证书，
     * 只可用于调用http或公有云的其他https服务，这些服务必须提供可信的证书
     */
    protected static final HttpUtil http;
    
    private static final Dispatcher dispatcher;
    private static final ConnectionPool connectionPool;
    private static RootTruster rootTrust;
    private static SSLContext sslContext;
    private static final HostnameVerifier hostnameVerifier;
    private static final List<HttpUtil> https = new ArrayList<>();

    static {
        /*
         * 【极其重要】本系统极大依赖服务间调用，maxRequestsPerHost默认为5个，
         * 一旦服务间调用并发量大于5时，就会出现等待超时，造成调用链上某一环失败，从而导致整个调用失败。
         * 此值如果太大，会导致Dispatcher的线程池暴增，所以两者之间需要仔细衡量。
          【注意】这段初始化工作比较耗时，在8C-1.4G的机器上超过2s
         */
        dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(500);
        dispatcher.setMaxRequestsPerHost(100);
        connectionPool = new ConnectionPool();

        String tlsVer = SecureUtil.getMaxTlsVer();
        try {
            rootTrust = RootTruster.create(ROOTCER);
            sslContext = SSLContext.getInstance(tlsVer);
            sslContext.init(null, new TrustManager[]{rootTrust}, SecureUtil.getRandom());
        } catch (Exception e) {
            LOG.error("Fail to create root trustmanager", e);
        }
        
        LOG.debug("Max tls version:{}", tlsVer);
        hostnameVerifier = (hostname, session) -> {
            try {
                Certificate[] certs = session.getPeerCertificates();
                if (certs != null && certs.length > 0 && certs[0] instanceof X509Certificate) {
                    X509Certificate cert = (X509Certificate) certs[0]; //第一个是证书链上最末端的证书
                    String peer = cert.getSubjectX500Principal().getName();
                    if (peer.contains(IConst.CER_DOMAIN)) { //不关心url中的域名，只关心证书中的域名
                        return true;
                    }
                }
            } catch (SSLPeerUnverifiedException e) {
                LOG.error("Fail to create root trustmanager,tls version:{}", tlsVer, e);
            }
            //不是内置域名CER_DOMAIN，则使用默认的校验
            return OkHostnameVerifier.INSTANCE.verify(hostname, session);
        };
        
        http = buildClient(HttpUtil.DEFAULT_CLIENT_TIMEOUT); //默认的请求客户端
    }
    
    public static HttpUtil getHttpClient(int timeout) {
        if(timeout <= HttpUtil.DEFAULT_CLIENT_TIMEOUT) {
            return http;
        }
        for(HttpUtil h : https) { //创建httputil是耗时操作，所以尽量使用已有的
            if(h.timeout >= timeout) {
                return h;
            }
        }
        HttpUtil hu = buildClient(timeout);
        https.add(hu);
        https.sort(Comparator.comparingInt(e -> e.timeout));
        return hu;
    }
    
    private static HttpUtil buildClient(int timeout) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .connectionPool(connectionPool)
            .dispatcher(dispatcher)
            .hostnameVerifier(hostnameVerifier)
            .sslSocketFactory(sslContext.getSocketFactory(), rootTrust);
        
        return new HttpUtil(builder, timeout);
    }

    /**
     * 基础的异步get调用，发生错误时，会将错误记录到node中
     * @param node    发送的目的节点，用于记录健康状态
     * @param fullUrl 完整的请求URL
     * @param headers 请求头
     * @param traceId 跟踪id，只用于打印
     * @return 异步执行结果
     */
    public static CompletableFuture<HandleResult> getFrom(NodeAddress node, String fullUrl, Map<String, String> headers, String traceId) {
        RestCallback future = new RestCallback(node, traceId);
        if(LOG.isDebugEnabled()) {
            LOG.debug("getFrom=>node:{},url:{},traceId:{}", node, fullUrl, traceId);
        }
        http.asyncGet(fullUrl,
            HttpUtil.putHeader(headers, HttpUtil.HEAD_USER_AGENT, MESH_CLIENT_AGENT),
            future);
        return future;
    }
    
    /**
     * 基础的异步get调用，发生错误时，会将错误记录到node中
     * @param node    发送的目的节点，用于记录健康状态
     * @param fullUrl 完整的请求URL
     * @param headers 请求头
     * @param traceId 跟踪id，只用于打印
     * @return 异步执行结果
     */
    public static CompletableFuture<ProxyResponse> getFileFrom(NodeAddress node, String fullUrl, Map<String, String> headers, String traceId) {
        ProxyBinaryCallback future = new ProxyBinaryCallback(node, traceId);
        if(LOG.isDebugEnabled()) {
            LOG.debug("getFileFrom=>node:{},url:{},traceId:{}", node, fullUrl, traceId);
        }
        http.asyncGet(fullUrl,
            HttpUtil.putHeader(headers, HttpUtil.HEAD_USER_AGENT, MESH_CLIENT_AGENT),
            future);
        return future;
    }

    /**
     * 异步的post请求，发生错误时，会将错误记录到node中
     *
     * @param node        发送的目的节点，用于记录健康状态
     * @param fullUrl     完整的请求URL
     * @param headers     请求头
     * @param contentType 消息体类型
     * @param body        请求体
     * @param traceId     跟踪id，只用于打印
     * @return 异步执行结果
     */
    public static CompletableFuture<HandleResult> postTo(NodeAddress node, String fullUrl,
        Map<String, String> headers, String contentType, String body, String traceId) {
        RestCallback future = new RestCallback(node, traceId);
        if(LOG.isDebugEnabled()) {
            LOG.debug("postTo=>node:{},url:`{}`,body:{},traceId:`{}`",
                    node, fullUrl, StringUtil.shorten(body, 80), traceId);
        }
        http.asyncPost(fullUrl,
            HttpUtil.putHeader(headers, HttpUtil.HEAD_USER_AGENT, MESH_CLIENT_AGENT),
            contentType, body, future);
        return future;
    }
    
    /**
     * 异步的post文件请求，发生错误时，会将错误记录到node中
     *
     * @param node        发送的目的节点，用于记录健康状态
     * @param fullUrl     完整的请求URL
     * @param headers     请求头
     * @param contentType 消息体类型
     * @param f           文件
     * @param traceId     跟踪id，只用于打印
     * @return 异步执行结果
     */
    public static CompletableFuture<HandleResult> postTo(NodeAddress node, String fullUrl,
        Map<String, String> headers, String contentType, File f, String traceId) {
        RestCallback future = new RestCallback(node, traceId);
        if(LOG.isDebugEnabled()) {
            LOG.debug("postTo_file=>node:{},url:{},traceId:{}", node, fullUrl, traceId);
        }
        http.asyncPost(fullUrl,
            HttpUtil.putHeader(headers, HttpUtil.HEAD_USER_AGENT, MESH_CLIENT_AGENT),
            contentType, f, future);
        return future;
    }

    /**
     * 异步的put请求，发生错误时，会将错误记录到node中
     *
     * @param node        发送的目的节点，用于记录健康状态
     * @param fullUrl     完整的请求URL
     * @param headers     请求头
     * @param contentType 消息体类型
     * @param body        请求体
     * @param traceId     跟踪id，只用于打印
     * @return 异步执行结果
     */
    public static CompletableFuture<HandleResult> putTo(NodeAddress node, String fullUrl,
        Map<String, String> headers, String contentType, String body, String traceId) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("putTo=>node:{},url:{},body:{},traceId:{}",
                    node, fullUrl, StringUtil.shorten(body, 80), traceId);
        }
        RestCallback future = new RestCallback(node, traceId);
        http.asyncPut(fullUrl,
            HttpUtil.putHeader(headers, HttpUtil.HEAD_USER_AGENT, MESH_CLIENT_AGENT),
            contentType, body, future);
        return future;
    }

    /**
     * 异步put调用，上传文件，发生错误时，会将错误记录到node中
     * @param node        发送的目的节点，用于记录健康状态
     * @param fullUrl         完整的请求URL
     * @param headers     请求头
     * @param contentType 消息体类型
     * @param f           待上传文件
     * @param traceId     跟踪id，只用于打印
     * @return 异步执行结果
     */
    public static CompletableFuture<HandleResult> putTo(NodeAddress node, String fullUrl,
        Map<String, String> headers, String contentType, File f, String traceId) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("putTo_file=>node:{},url:{},traceId:{}", node, fullUrl, traceId);
        }
        RestCallback future = new RestCallback(node, traceId);
        http.asyncPut(fullUrl,
            HttpUtil.putHeader(headers, HttpUtil.HEAD_USER_AGENT, MESH_CLIENT_AGENT),
            contentType, f, future);
        return future;
    }
    
    /**
     * 基础的异步delete调用，发生错误时，会将错误记录到node中
     * @param node    发送的目的节点，用于记录健康状态
     * @param fullUrl 完整的请求URL
     * @param headers 请求头
     * @param traceId 跟踪id，只用于打印
     * @return 异步执行结果
     */
    public static CompletableFuture<HandleResult> delFrom(NodeAddress node, String fullUrl,
        Map<String, String> headers, String traceId) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("delFrom=>node:{},url:{},traceId:{}", node, fullUrl, traceId);
        }
        RestCallback future = new RestCallback(node, traceId);
        http.asyncDelete(fullUrl,
            HttpUtil.putHeader(headers, HttpUtil.HEAD_USER_AGENT, MESH_CLIENT_AGENT),
            future);
        return future;
    }
    
    /**
     * 异步post调用，content-type为JSON，发生错误时，会将错误记录到node中
     * @param node    发送的目的节点，用于记录健康状态
     * @param url     完整的请求URL
     * @param headers 请求头
     * @param body    请求体，必须是json字符串
     * @param traceId 跟踪id，只用于打印
     * @return 异步执行结果
     */
    public static CompletableFuture<HandleResult> postJsonTo(NodeAddress node, String url,
        Map<String, String> headers, String body, String traceId) {
        return postTo(node, url, headers, HttpUtil.CONTENT_TYPE_JSON, body, traceId);
    }
    
    public static CompletableFuture<HandleResult> postJsonTo(NodeAddress node, String url,
        Map<String, String> headers, Map<String, Object> reqData, String traceId) {
        return postTo(node, url, headers, HttpUtil.CONTENT_TYPE_JSON, JsonUtil.objToJson(reqData), traceId);
    }

    /**
     * 异步put调用，content-type为JSON，发生错误时，会将错误记录到node中
     * @param node    发送的目的节点，用于记录健康状态
     * @param fullUrl 完整的请求URL
     * @param headers 请求头
     * @param body    请求体
     * @param traceId 跟踪id，只用于打印
     * @return 异步执行结果
     */
    public static CompletableFuture<HandleResult> putJsonTo(NodeAddress node, String fullUrl,
        Map<String, String> headers, String body, String traceId) {
        return putTo(node, fullUrl, headers, HttpUtil.CONTENT_TYPE_JSON, body, traceId);
    }

    public static void close() {
        http.close();
    }


    /**
     * 拼接接口url
     * @param scheme  比如 http/https
     * @param service 服务名
     * @param url     服务url
     * @param addr    地址+端口，可以是域名
     * @return        拼接后的url
     */
    public static String serviceApiUrl(String scheme, String service, String url, String addr) {
        String base = scheme + addr + '/' + service;
        if (!url.startsWith(URL_API)) { //没有/api则加上它
            base += URL_API;
        }

        if(url.charAt(0) == '/') {
            return base + url;
        }

        return base + '/' + url;
    }

    /**
     * 拼接文件url
     * @param scheme  比如 http/https
     * @param service 服务名
     * @param url     服务url
     * @param addr    地址，可以是域名
     * @return        拼接后的url
     */
    public static String serviceFileUrl(String scheme, String service, String url, String addr) {
        String base = scheme + addr + '/' + service;
        if (url.charAt(0) == '/') {
            return base + url;
        }
        return base + '/' + url;
    }
    //-------------------------------------------------------------------------
    /**
     * 伪装成普通浏览器，异步调用外部url，返回一个字符串。无node，所以不会记录错误
     *
     * @param fullUrl 完整url，包括scheme、domain等
     * @param headers 请求头
     * @return 异步GET调用结果
     */
    public static CompletableFuture<String> disguiserGet(String fullUrl, Map<String, String> headers) {
        StringCallback future = new StringCallback(null, StringUtil.base64UUID());
        http.asyncGet(fullUrl,
                HttpUtil.putHeader(headers, HttpUtil.HEAD_USER_AGENT, HttpUtil.DISGUISER),
                future);
        return future;
    }
    
    /**
     * 异步调用外部url，返回一个字符串。无node，所以不会记录错误
     *
     * @param fullUrl 完整url，包括scheme、domain等
     * @param headers 请求头
     * @return 异步GET调用结果
     */
    public static CompletableFuture<String> directlyGet(String fullUrl, Map<String, String> headers) {
        StringCallback future = new StringCallback(null, StringUtil.base64UUID());
        http.asyncGet(fullUrl, headers, future);
        return future;
    }

    /**
     * 异步调用外部url，返回二进制内容。无node，所以不会记录错误
     *
     * @param url     完整url，包括scheme、domain等
     * @param headers 请求头
     * @return 异步GET调用结果
     */
    public static CompletableFuture<byte[]> getExternalBinary(String url, Map<String, String> headers) {
        BinaryCallback future = new BinaryCallback(null, StringUtil.base64UUID());
        http.asyncGet(url,
                HttpUtil.putHeader(headers, HttpUtil.HEAD_USER_AGENT, HttpUtil.DISGUISER),
                future);
        return future;
    }

    /**
     * 异步GET调用外部接口。无node，所以不会记录错误
     * @param fullUrl 完整url，包括scheme、domain等
     * @param headers 请求头
     * @return GET调用的异步响应
     */
    public static CompletableFuture<HandleResult> getFrom(String fullUrl, Map<String, String> headers, String traceId) {
        RestCallback future = new RestCallback(null, traceId);
        http.asyncGet(fullUrl,
                HttpUtil.putHeader(headers, HttpUtil.HEAD_USER_AGENT, MESH_CLIENT_AGENT),
                future);
        return future;
    }

    /**
     * 上传文件。无node，所以不会记录错误
     * @param url   完整url，包括scheme、domain等
     * @param headers 请求头
     * @param contentType 文件类型
     * @param f 待上传文件
     * @return 异步执行结果
     */
    public static CompletableFuture<String> putTo(String url, Map<String, String> headers, String contentType, File f) {
        StringCallback future = new StringCallback(null, StringUtil.base64UUID());
        http.asyncPut(url, headers, contentType, f, future);
        return future;
    }

    /**
     * 下载较大的资源，只支持get方法。无node，所以不会记录错误
     * @param url     完整的资源url
     * @param headers 请求头
     * @param saveAs  下载后保存的文件
     * @return 异步结果
     */
    public static CompletableFuture<HandleResult> download(String url, Map<String, String> headers, String saveAs) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("Download `{}` from `{}`", saveAs, url);
        }
        DownloadCallback future = new DownloadCallback(null, StringUtil.base64UUID(), saveAs);
        http.asyncGet(url, headers, future);
        return future;
    }

    public static CompletableFuture<HandleResult> download(String url, String saveAs) {
        return download(url, null, saveAs);
    }

    /**
     * 从任意网站下载较大的文件，下载进度通过process通知给调用方
     * @param url 完整的url
     * @param saveAs 另存为
     * @param progress 进度回调
     * @return 处理结果
     */
    public static CompletableFuture<HandleResult> download(String url, String saveAs, AbsHttpCallback.IDownloadProgress progress) {
        Map<String, String> h = HttpUtil.putHeader(null, HttpUtil.HEAD_USER_AGENT, HttpUtil.DISGUISER);
        BigFileDownloadCallback future = new BigFileDownloadCallback(null, "", saveAs, progress);
        http.asyncGet(url, h, future);
        return future;
    }

    /**
     * 访问外部url时，以此Stream，解决同步阻塞问题
     * @param url 外部完整的url
     * @param headers 请求头
     * @return 输入流
     */
    public static HttpUtil.UrlInputStream urlInputStream(String url, Map<String, String> headers) {
        return new HttpUtil.UrlInputStream(http, url, headers);
    }
    //-------------------------------------------------------------------------
    /**
     * 服务请求构造器
     * @author flyinmind of csdn.net
     */
    public static class ServiceReqBuilder {
        public final ServiceInfo caller;
        public final String callee;

        protected final Map<String, String> headers;
        protected final Map<String, Object> data;
        
        protected int cid = IConst.LOCAL_COMPANY_ID;
        protected String url;
        protected String traceId;
        protected String token;
        protected int retryTimes = 0;
        private String body = null; //请求消息体，如未设置，则使用data
        private boolean firstPara; //用为url追加参数
        private int nodeId = -1;

        /**
         * 构造函数
         * @param caller 调用方信息
         * @param callee 被调方名称
         */
        public ServiceReqBuilder(ServiceInfo caller, String callee) {
            this.caller = caller;
            this.callee = callee;
            this.headers = new HashMap<>();
            this.data = new HashMap<>();
        }

        public ServiceReqBuilder url(String url) {
            this.url = url;
            this.firstPara = url.indexOf('?') < 0;
            return this;
        }

        public String url() {
            return this.url;
        }

        public ServiceReqBuilder header(String name, String val) {
            if(StringUtil.isEmpty(val)) {
                return this;
            }
            this.headers.put(name, val);
            return this;
        }

        public ServiceReqBuilder headers(Map<String, String> headers) {
            if(headers == null) {
                return this;
            }
            this.headers.putAll(headers);
            return this;
        }

        public ServiceReqBuilder traceId(String traceId) {
            this.traceId = traceId;
            header(IConst.HEAD_TRACE_ID, traceId);
            return this;
        }

        public ServiceReqBuilder token(String token) {
            this.token = token;
            header(IOAuth.HEAD_ACCESS_TOKEN, token);
            return this;
        }
        
        public ServiceReqBuilder put(String k, Object v) {
            data.put(k, v);
            return this;
        }

        public ServiceReqBuilder putAll(Map<String, Object> map) {
            data.putAll(map);
            return this;
        }
        
        public Map<String, Object> data() {
            return Collections.unmodifiableMap(data);
        }

        public ServiceReqBuilder cid(int cid) {
            this.cid = cid;
            this.headers.put(IConst.HEAD_CID, Integer.toString(cid));
            return this;
        }
        
        public int cid() {
            return this.cid;
        }

        /**
         * 产生发出请求的服务的token
         * @param features 需要调用的特性
         * @return ServiceReqBuilder
         */
        public ServiceReqBuilder appToken(String features) {
            if(caller.tokenWorker == null) {
                LOG.warn("[{}]No token work for {}->{},url:{}", traceId, caller.name, callee, url);
                return this; //运行时加入的服务，无appTokenWorker
            }
            this.token = BiosClient.appToken(caller, callee, features);
            header(IOAuth.HEAD_ACCESS_TOKEN, token);
            return this;
        }
        
        public ServiceReqBuilder body(String body) {
            this.body = body;
            return this;
        }

        public ServiceReqBuilder appendParas(Map<String, Object> params) {
            for(Map.Entry<String, Object> p : params.entrySet()) {
                appendPara(p.getKey(), ValParser.parseString(p.getValue()));
            }
            return this;
        }

        public ServiceReqBuilder appendPara(String k, String v) {
            return appendPara(k, v, true);
        }
        
        public ServiceReqBuilder appendPara(String k, String v, boolean encode) {
            String val = encode ? UrlPathInfo.urlEncode(v) : v;
            if(firstPara) {
                this.url += '?' + k + '=' + val;
            } else {
                this.url += '&' + k + '=' + val;
            }
            firstPara = false;
            return this;
        }
        
        public ServiceReqBuilder nodeId(int id) {
            this.nodeId = id;
            return this;
        }

        public int retry() {
            retryTimes++;
            return retryTimes;
        }
        
        //输出body前，用于调整data的内容，比如添加或覆盖一些字段
        void adjustData() {
        }
        
        /**
         * 如果未设置body，则返回data的json体
         * @return body
         */
        public String body() {
            if(body != null) {
                return body;
            }
            adjustData();
            return JsonUtil.objToJson(data);
        }

        public int nodeId() { //用于确定调用的节点
            if(nodeId < 0) {
                nodeId = token != null ? ValParser.absInt(token.hashCode()) : cid;
            }
            return nodeId;
        }

        @Override
        public String toString() {
            return "url:\"" + StringUtil.shorten(url, 50)
                    + "\",caller:\"" + caller.name + "\",callee:\"" + callee
                    + "\",retry:" + retryTimes
                    + ",headers:" + headers;
        }
    }
}