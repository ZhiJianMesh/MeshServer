package cn.net.zhijian.mesh.client;

import java.io.File;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

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

/**
 * 内部访问http的客户端，只用于访问服务端接口。
 * 基于JDK11内置的java.net.http.HttpClient，并装载了内置私有根证书（ROOTCER），
 * 内部https服务与公有证书的外部https服务都可访问，证书校验见MeshTrustManager。
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
     * 内部服务间调用的客户端，已装载内置私有根证书（ROOTCER + MeshTrustManager），
     * 既可以访问内部https服务，也可以访问使用公有证书的外部https服务
     */
    protected static final HttpUtil http;

    private static RootTruster rootTrust;
    private static SSLContext sslContext;
    private static final java.net.http.HttpClient jdkClient;
    private static final List<HttpUtil> https = new ArrayList<>();

    static {
        /*
         * 【重要】JDK的HttpClient默认禁止设置host、content-length、date等受限请求头，
         * 而OSS签名请求必须显式带date，UrlRequest.headers里也带host/content-length。
         * 该系统属性在jdk.internal.net.http.common.Utils初始化时读取，
         * 必须在任何java.net.http类被使用前设置，所以放在本静态块的第一行。
         */
        System.setProperty("jdk.httpclient.allowRestrictedHeaders",
                "host,content-length,date,connection,upgrade,expect,from,via,warning");

        /*
         * 【极其重要】本系统极大依赖服务间调用。旧okhttp的Dispatcher默认每host只有5个并发，
         * 超过就排队等待直至超时；JDK客户端由连接池与HTTP/2多路复用管理并发，
         * 不再有maxRequestsPerHost这个瓶颈，也无需共享Dispatcher/ConnectionPool。
         */
        String tlsVer = SecureUtil.getMaxTlsVer();
        try {
            rootTrust = RootTruster.create(ROOTCER);
            sslContext = SSLContext.getInstance(tlsVer);
            // 内置私有根证书 + 证书域名白名单 + 标准主机名校验，全部在MeshTrustManager里
            sslContext.init(null, new TrustManager[]{new MeshTrustManager(rootTrust)},
                    SecureUtil.getRandom());
        } catch (Exception e) {
            LOG.error("Fail to create root trustmanager", e);
            try {
                sslContext = SSLContext.getDefault(); //降级为系统默认证书，保证服务能启动
            } catch (Exception e2) {
                LOG.error("Fail to get default SSLContext", e2);
            }
        }

        LOG.debug("Max tls version:{}", tlsVer);
        // 关闭JSSE内置的endpoint identification，主机名校验改由MeshTrustManager完成，
        // 这样才能保留"证书SubjectDN含CER_DOMAIN就放行"的白名单逻辑
        SSLParameters sslParams = new SSLParameters();
        sslParams.setEndpointIdentificationAlgorithm("");

        jdkClient = java.net.http.HttpClient.newBuilder()
            .sslContext(sslContext)
            .sslParameters(sslParams)
            .version(java.net.http.HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofMillis(HttpUtil.DEFAULT_CLIENT_TIMEOUT))
            .build();

        http = buildClient(HttpUtil.DEFAULT_CLIENT_TIMEOUT); //默认的请求客户端
    }

    /**
     * 内置私有根证书 + 主机名校验器。
     * <p>
     * java.net.http.HttpClient没有HostnameVerifier回调，主机名校验由JSSE在TLS握手时完成，
     * 所以必须实现X509ExtendedTrustManager（而不是X509TrustManager），
     * 才能在带SSLEngine的重载里拿到peerHost与证书链。
     * <ul>
     *  <li>证书链校验：完全委托原有的RootTruster（内置根证书，否则回落到系统信任库）</li>
     *  <li>内置证书：SubjectDN含CER_DOMAIN即放行，不校验url中的host（内部用IP访问）</li>
     *  <li>其它证书：按subjectAltName(dNSName/iPAddress)、通配符、CN回退自行校验主机名</li>
     * </ul>
     * 【注意】不能用HttpsURLConnection.getDefaultHostnameVerifier()取代OkHostnameVerifier：
     * 它只是HttpsClient用于判断是否走内置HostnameChecker的哨兵对象，verify()恒返回false。
     */
    private static final class MeshTrustManager extends X509ExtendedTrustManager {
        private final RootTruster delegate;

        MeshTrustManager(RootTruster delegate) {
            this.delegate = delegate;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            verifyHost(chain, engine == null ? null : engine.getPeerHost());
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            verifyHost(chain, socket == null || socket.getInetAddress() == null
                    ? null : socket.getInetAddress().getHostAddress());
        }

        private void verifyHost(X509Certificate[] chain, String host)
                throws CertificateException {
            if(chain == null || chain.length == 0) {
                return;
            }
            String peer = chain[0].getSubjectX500Principal().getName();
            if(peer.contains(IConst.CER_DOMAIN)) { //不关心url中的域名，只关心证书中的域名
                return;
            }
            //不是内置域名CER_DOMAIN，则按SAN/CN校验主机名
            if(!hostnameMatches(host, chain[0])) {
                throw new CertificateException("Hostname verification failed:" + host);
            }
        }

        /** 按RFC 2818/6125校验主机名：优先subjectAltName，无SAN时才回退CN */
        private static boolean hostnameMatches(String host, X509Certificate cert) {
            if(StringUtil.isEmpty(host)) {
                return false;
            }
            String h = host.toLowerCase(Locale.ROOT);
            if(h.startsWith("[") && h.endsWith("]")) { //IPv6的方括号
                h = h.substring(1, h.length() - 1);
            }
            if(h.endsWith(".")) { //完全限定域名末尾的点
                h = h.substring(0, h.length() - 1);
            }
            boolean ip = isIpAddress(h);
            try {
                Collection<List<?>> sans = cert.getSubjectAlternativeNames();
                if(sans != null) {
                    for(List<?> san : sans) {
                        if(san == null || san.size() < 2 || !(san.get(0) instanceof Number)) {
                            continue;
                        }
                        int type = ((Number) san.get(0)).intValue();
                        Object val = san.get(1);
                        if(val == null) {
                            continue;
                        }
                        String name = String.valueOf(val).toLowerCase(Locale.ROOT);
                        if((ip && type == 7 && h.equals(name))
                                || (!ip && type == 2 && matchesDns(h, name))) {
                            return true;
                        }
                    }
                    return false; //证书带了SAN，就不再回退到CN
                }
            } catch (CertificateParsingException e) {
                LOG.warn("Fail to parse subjectAltNames of {}", cert.getSubjectX500Principal(), e);
            }
            return matchesDns(h, commonName(cert.getSubjectX500Principal().getName()));
        }

        private static boolean matchesDns(String host, String pattern) {
            if(pattern == null) {
                return false;
            }
            String p = pattern.toLowerCase(Locale.ROOT);
            if(p.startsWith("*.")) {
                String suffix = p.substring(2);
                int dot = host.indexOf('.');
                return dot > 0 && !suffix.isEmpty() && host.substring(dot + 1).equals(suffix);
            }
            return host.equals(p);
        }

        private static boolean isIpAddress(String h) {
            if(h.indexOf(':') >= 0) { //IPv6
                return true;
            }
            String[] parts = h.split("\\.", -1);
            if(parts.length != 4) {
                return false;
            }
            for(String p : parts) {
                if(p.isEmpty() || p.length() > 3) {
                    return false;
                }
                for(int i = 0; i < p.length(); i++) {
                    if(p.charAt(i) < '0' || p.charAt(i) > '9') {
                        return false;
                    }
                }
            }
            return true;
        }

        private static String commonName(String dn) {
            try {
                for(Rdn rdn : new LdapName(dn).getRdns()) {
                    if("CN".equalsIgnoreCase(rdn.getType())) {
                        return String.valueOf(rdn.getValue());
                    }
                }
            } catch (InvalidNameException e) {
                LOG.debug("Fail to parse DN:{}", dn, e);
            }
            return null;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            delegate.checkClientTrusted(chain, authType); //双向认证才会用到，RootTruster未实现
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
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
        // 所有实例共享同一个带私有根证书的JDK客户端；请求超时由每个HttpRequest单独设置，
        // 按timeout缓存HttpUtil只是为了复用，不再重复创建连接池与线程
        return new HttpUtil(jdkClient, timeout);
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