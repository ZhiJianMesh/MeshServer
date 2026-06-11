package cn.net.zhijian.mesh.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.bean.ProxyResponse;
import cn.net.zhijian.mesh.bean.ServiceDNS;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbstractDNS;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.httpcb.DownloadCallback;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 服务调用客户端
 * @author flyinmind of csdn.net
 */
public class ServiceClient extends HttpClient implements IThreadPool, IOAuth {
    private static final Logger LOG = LogUtil.getInstance();
    private static final int MAX_RETRY_TIMES = 2;
    private static final String BIOS_API_SERVICENODES = "/service/nodes";
    private static final String CLOUD_SCHEME = "https://";
    private static final long CDN_CACHE_TIME = 3600000L;
    //本地服务的DNS
    protected static final Map<String, AbstractDNS> LocalDNS = new ConcurrentHashMap<>();
    //公有云服务的DNS
    protected static final Map<String, AbstractDNS> CloudDNS = new ConcurrentHashMap<>();
    /**
     * 本服务作为调用方时的token缓存，这里没有考虑token缓存的并发，
     * 因为服务间token不会太多，且每个token占用内存不多，所以将之缓存
     */
    private static final Map<Long, AccessToken> ServiceTokenCache = new ConcurrentHashMap<>();
   
    private static final List<String> CdnList = new ArrayList<>();
    private static long cdnCacheTime = 0L;
    
    private static final String TRACE_HEAD = "ZJ_ADR_Srv";
    //用于请求公有云侧服务，并无其他作用，最终要设置成内置的backend服务的信息
    //在设置成内置backend之前，先赋默认值防止空指针异常，
    //在这之前，不能用它的tokenworker，也就是说，请求时不能用ServiceReqBuilder.appToken
    private static ServiceInfo BackendService;
    //-------------------------------------------------------------------------
    /**
     * 直接使用地址（包括断开）进行post访问，如果发生错误，不会报告给node
     * @param addr 地址
     * @param req 请求构造器
     * @return post结果
     */
    public static CompletableFuture<HandleResult> servicePost(String addr, ServiceReqBuilder req) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("servicePost({}) to {}", req, addr);
        }
        if(req.cid < 0) {
            return HandleResult.future(RetCode.DATA_WRONG, "cid head not set");
        }
        String fullUrl = serviceApiUrl(SCHEME, req.callee, req.url, addr);
        return postJsonTo(null, fullUrl, req.headers, req.body(), req.traceId);
    }

    public static CompletableFuture<HandleResult> servicePost(NodeAddress node, ServiceReqBuilder req) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("servicePost({})", req);
        }
        if(req.cid < 0) {
            return HandleResult.future(RetCode.DATA_WRONG, "cid head not set");
        }
        String fullUrl = serviceApiUrl(SCHEME, req.callee, req.url, node.addr);
        return postJsonTo(node, fullUrl, req.headers, req.body(), req.traceId);
    }

    /**
     * 异步post调用，由caller、callee共同决定被调服务实例的IP
     * @param req 请求构造器
     * @return 异步post结果
     */
    public static CompletableFuture<HandleResult> servicePost(ServiceReqBuilder req) {
        if(req.cid < 0) {
            return HandleResult.future(RetCode.DATA_WRONG, "cid head not set");
        }
        return serviceLookup(req).thenComposeAsync((node)-> {
            if(node == null) {
                return HandleResult.future(RetCode.SERVICE_NOT_FOUND);
            }
            return servicePost(node, req).exceptionally(e -> {
                LOG.error("retry servicePost {}", req, e);
                if(req.retry() < MAX_RETRY_TIMES) {
                    return servicePost(req).join();
                }
                return new HandleResult(RetCode.HTTP_ERROR, "fail to request");
            });
        }, Pool);
    }

    public static CompletableFuture<HandleResult> servicePut(NodeAddress node, ServiceReqBuilder req) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("servicePut({})", req);
        }
        if(req.cid < 0) {
            return HandleResult.future(RetCode.DATA_WRONG, "cid head not set");
        }
        String fullUrl = serviceApiUrl(SCHEME, req.callee, req.url, node.addr);
        return putJsonTo(node, fullUrl, req.headers, req.body(), req.traceId);
    }

    public static CompletableFuture<HandleResult> servicePut(ServiceReqBuilder req) {
        return serviceLookup(req).thenComposeAsync((node)-> {
            if(node == null) {
                return HandleResult.future(RetCode.SERVICE_NOT_FOUND);
            }
            return servicePut(node, req).exceptionally(e -> {
                LOG.error("retry servicePut {}", req, e);
                if(req.retry() < MAX_RETRY_TIMES) {
                    return servicePut(req).join();
                }
                return new HandleResult(RetCode.HTTP_ERROR, "fail to request");
            });
        }, Pool);
    }

    /**
     * 服务间异步get调用
     * 失败重试解决方法参照以下链接
     * @see <a href="https://stackoverflow.com/questions/40485398/retry-logic-with-completablefuture">
     *     https://stackoverflow.com/questions/40485398/retry-logic-with-completablefuture</a>
     * @param req 请求构造器
     * @return 异步调用结果
     */
    public static CompletableFuture<HandleResult> serviceGet(ServiceReqBuilder req) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("serviceGet({})", req);
        }
        if(req.cid < 0) {
            return HandleResult.future(RetCode.DATA_WRONG, "cid head not set");
        }
        return serviceLookup(req).thenComposeAsync((node)-> {
            if(node == null) {
                return HandleResult.future(RetCode.SERVICE_NOT_FOUND);
            }
            String fullUrl = serviceApiUrl(SCHEME, req.callee, req.url, node.addr);
            return getFrom(node, fullUrl, req.headers, req.traceId).exceptionally(e -> {
                LOG.error("retry serviceGet {}", req, e);
                if(req.retry() < MAX_RETRY_TIMES) {
                    return serviceGet(req).join();
                }
                return new HandleResult(RetCode.HTTP_ERROR, "fail to request");
            });
        }, Pool);
    }
    
    /**
     * 异步get文件，内容一次性返回，不适合请求超大文件
     * 失败重试解决方法参照以下链接
     * @see <a href="https://stackoverflow.com/questions/40485398/retry-logic-with-completablefuture">
     *     https://stackoverflow.com/questions/40485398/retry-logic-with-completablefuture</a>
     * @param req 请求构造器
     * @return 异步调用结果，不存在或异常返回null
     */
    public static CompletableFuture<ProxyResponse> fileGet(ServiceReqBuilder req) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("serviceGet({})", req);
        }
        if(req.cid < 0) {
            return CompletableFuture.completedFuture(null);
        }
        return serviceLookup(req).thenComposeAsync((node)-> {
            if(node == null) {
                return CompletableFuture.completedFuture(null);
            }
            String fullUrl = serviceFileUrl(SCHEME, req.callee, req.url, node.addr);
            return getFileFrom(node, fullUrl, req.headers, req.traceId).exceptionally(e -> {
                LOG.error("retry fileGet {}", req, e);
                if(req.retry() < MAX_RETRY_TIMES) {
                    return fileGet(req).join();
                }
                return null;
            });
        }, Pool);
    }

    /**
     * 异步删除请求
     * @param req 请求构造器
     * @return 异步结果
     */
    public static CompletableFuture<HandleResult> serviceDelete(ServiceReqBuilder req) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("serviceDelete({})", req);
        }
        if(req.cid < 0) {
            return HandleResult.future(RetCode.DATA_WRONG, "cid head not set");
        }
        return serviceLookup(req).thenComposeAsync((node)-> {
            if(node == null) {
                return HandleResult.future(RetCode.SERVICE_NOT_FOUND);
            }
            String fullUrl = serviceApiUrl(SCHEME, req.callee, req.url, node.addr);
            return delFrom(node, fullUrl, req.headers, req.traceId).exceptionally(e -> {
                LOG.error("retry serviceDelete {}", req, e);
                if(req.retry() < MAX_RETRY_TIMES) {
                    return serviceDelete(req).join();
                }
                return new HandleResult(RetCode.HTTP_ERROR, "fail to request");
            });
        }, Pool);
    }
    
    public static CompletableFuture<HandleResult> serviceDelete(NodeAddress node, ServiceReqBuilder req) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("serviceDelete({}) with node", req);
        }
        if(req.cid < 0) {
            return HandleResult.future(RetCode.DATA_WRONG, "cid head not set");
        }
        String fullUrl = serviceApiUrl(SCHEME, req.callee, req.url, node.addr);
        return delFrom(node, fullUrl, req.headers, req.traceId);
    }

    /**
     * 直接通过addr进行访问，发生错误不会向node报告
     * @param addr 请求地址
     * @param callee 被调方
     * @param url 请求url
     * @param headers 请求头
     * @param traceId 跟踪id
     * @return 异步get结果
     */
    public static CompletableFuture<HandleResult> serviceGet(String addr,
        String callee, String url, Map<String, String> headers, String traceId) {
        String fullUrl = serviceApiUrl(SCHEME, callee, url, addr);
        return getFrom(fullUrl, headers, traceId);
    }
    
    /**
     * 异步方式无token的get，只能用于调用public接口
     * 请求之前先要解析服务对应的服务器地址，但是无需获取token。
     * @param req 请求构造器
     * @return 异步get结果
     */
    public static CompletableFuture<HandleResult> getPublic(ServiceReqBuilder req) {
        if(req.cid < 0) {
            return HandleResult.future(RetCode.DATA_WRONG, "cid head not set");
        }
        return serviceGet(req);
    }
    
    /**
     * 服务间异步get调用，
     * 先向oAuth2请求token，然后再携带token，向服务发起请求
     * @param req 请求构造器
     * @return 异步get结果
     */
    public static CompletableFuture<HandleResult> getPrivate(ServiceReqBuilder req) {
        if(req.cid < 0) {
            return HandleResult.future(RetCode.DATA_WRONG, "cid head not set");
        }
        return serviceToken(req).thenComposeAsync((token)-> {
            if(StringUtil.isEmpty(token)) {
                return HandleResult.future(RetCode.NO_RIGHT, "fail to get token");
            }
            req.token(token); //注意：这是服务间的token
            return serviceGet(req);
        }, Pool);
    }
    
    public static CompletableFuture<HandleResult> deletePrivate(ServiceReqBuilder req) {
        if(req.cid < 0) {
            return HandleResult.future(RetCode.DATA_WRONG, "cid head not set");
        }
        return serviceToken(req).thenComposeAsync((token)-> {
            if(StringUtil.isEmpty(token)) {
                return HandleResult.future(RetCode.NO_RIGHT, "fail to get token");
            }
            req.token(token); //注意：这是服务间的token
            return serviceDelete(req);
        }, Pool);
    }

    /**
     * 服务间异步post调用，
     * 先向oAuth2请求token，然后再携带token，向服务发起请求，所以请求时，无需携带token。
     * 前面获取的token，会缓存。
     * @param req 请求构造器
     * @return 异步post结果
     */
    public static CompletableFuture<HandleResult> postPrivate(ServiceReqBuilder req) {
        if(req.cid < 0) {
            return HandleResult.future(RetCode.DATA_WRONG, "cid head not set");
        }
        return serviceToken(req).thenComposeAsync((token) -> {
            if(StringUtil.isEmpty(token)) {
                return HandleResult.future(RetCode.NO_RIGHT, "fail to get token");
            }
            req.token(token);
            return servicePost(req);
        }, Pool);
    }
    
    public static CompletableFuture<HandleResult> putPrivate(ServiceReqBuilder req) {
        if(req.cid < 0) {
            return HandleResult.future(RetCode.DATA_WRONG, "cid head not set");
        }
        return serviceToken(req).thenComposeAsync((token) -> {
            if(StringUtil.isEmpty(token)) {
                return HandleResult.future(RetCode.NO_RIGHT, "fail to get token");
            }
            req.token(token);
            return servicePut(req);
        }, Pool);
    }
    //-------------------------------------------------------------------------
    public static void setBackendService(ServiceInfo si) {
        BackendService = si;
    }
    
    public static ServiceInfo backendService() {
        return BackendService;
    }

    public static ServiceReqBuilder backendReqBuilder(String callee) {
        return new ServiceReqBuilder(BackendService, callee);
    }

    /**
     * 异步GET调用公有云服务的接口
     * 如果请求的云上服务器作为bridge运行，因为请求公共服务，所以bridge不会进行转发，
     * 否则会出现无限循环转发中。
     * @param req 请求体
     * @return 异步调用结果
     */
    public static CompletableFuture<HandleResult> cloudGet(ServiceReqBuilder req) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("cloudGet({})", req);
        }
        return cloudLookup(req).thenComposeAsync((node)-> {
            if(node == null) {//找不到，则直接失败
                LOG.error("Can't find {}'s node when cloudGet {}", req.callee, req.url);
                return HandleResult.future(RetCode.NO_NODE);
            }
            String fullUrl = serviceApiUrl(CLOUD_SCHEME, req.callee, req.url, node.addr);
            return getFrom(node, fullUrl, req.headers, req.traceId).exceptionally(e -> {
                LOG.error("retry cloudGet {}", req, e);
                if(req.retry() < MAX_RETRY_TIMES) {
                    return cloudGet(req).join();
                }
                return new HandleResult(RetCode.HTTP_ERROR, "fail to request");
            });
        }, Pool);
    }

    /**
     * 异步DELETE调用公有云服务的接口
     * 如果请求的云上服务器作为bridge运行，因为请求公共服务，所以bridge不会进行转发，
     * 否则会出现无限循环转发中。
     * @param req 请求体
     * @return 异步调用结果
     */
    public static CompletableFuture<HandleResult> cloudDelete(ServiceReqBuilder req) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("cloudDelete({})", req);
        }
        return cloudLookup(req).thenComposeAsync((node)-> {
            if(node == null) {//找不到，则直接失败
                LOG.error("Can't find {}'s node when cloudDelete {}", req.callee, req.url);
                return HandleResult.future(RetCode.SERVICE_NOT_FOUND);
            }
            String fullUrl = serviceApiUrl(CLOUD_SCHEME, req.callee, req.url, node.addr);
            return delFrom(node, fullUrl, req.headers, req.traceId).exceptionally(e -> {
                LOG.error("retry cloudDelete {}", req, e);
                if(req.retry() < MAX_RETRY_TIMES) {
                    return cloudDelete(req).join();
                }
                return new HandleResult(RetCode.HTTP_ERROR, "fail to request");
            });
        }, Pool);
    }

    /**
     * 异步POST调用公有云服务的接口
     * 如果请求的云上服务器作为bridge运行，因为请求公共服务，所以bridge不会进行转发，
     * 否则会出现无限循环转发中。
     * @param req 请求体
     * @return 异步调用结果
     */
    public static CompletableFuture<HandleResult> cloudPost(ServiceReqBuilder req) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("cloudPost({})", req);
        }
        return cloudLookup(req).thenComposeAsync((node)-> {
            if(node == null) {//找不到，则直接失败
                LOG.error("Can't find {}'s node when cloudPost {}", req.callee, req.url);
                return HandleResult.future(RetCode.SERVICE_NOT_FOUND);
            }
            String fullUrl = serviceApiUrl(CLOUD_SCHEME, req.callee, req.url, node.addr);
            return postJsonTo(node, fullUrl, req.headers, req.body(), req.traceId).exceptionally(e -> {
                LOG.error("retry cloudPost {}", req, e);
                if(req.retry() < MAX_RETRY_TIMES) {
                    return cloudPost(req).join();
                }
                return new HandleResult(RetCode.HTTP_ERROR, "fail to request");
            });
        }, Pool);
    }

    /**
     * 异步PUT调用公有云服务的接口
     * 如果请求的云上服务器作为bridge运行，因为请求公共服务，所以bridge不会进行转发，
     * 否则会出现无限循环转发中。
     * @param req 请求体
     * @return 异步调用结果
     */
    public static CompletableFuture<HandleResult> cloudPut(ServiceReqBuilder req) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("cloudPut({})", req);
        }
        return cloudLookup(req).thenComposeAsync((node)-> {
            if(node == null) {//找不到，则直接失败
                LOG.error("Can't find {}'s node when cloudPut {}", req.callee, req.url);
                return HandleResult.future(RetCode.SERVICE_NOT_FOUND);
            }
            String fullUrl = serviceApiUrl(CLOUD_SCHEME, req.callee, req.url, node.addr);
            return putJsonTo(node, fullUrl, req.headers, req.body(), req.traceId).exceptionally(e -> {
                LOG.error("retry cloudPut {}", req, e);
                if(req.retry() < MAX_RETRY_TIMES) {
                    return cloudPut(req).join();
                }
                return new HandleResult(RetCode.HTTP_ERROR, "fail to request");
            });
        }, Pool);
    }

    /**
     * 从云侧下载较大的资源，比如安装包
     * @param service 提供资源的服务
     * @param url     服务接口url
     * @param saveAs  下载后保存的文件
     * @return 异步结果
     */
    public static CompletableFuture<HandleResult> cloudDownload(ServiceInfo caller, String service, String url, String saveAs) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("cloudDownload({}.{})", service, url);
        }
        ServiceReqBuilder req = new ServiceReqBuilder(caller, service)
                .url(url)
                .cid(IConst.ROOT_COMPANY_ID)
                .traceId(TRACE_HEAD + service);
        return cloudLookup(req).thenComposeAsync((node)-> {
            if (node == null) {
                LOG.error("Can't find {}'s node when cloudDownload {}", service, url);
                return HandleResult.future(RetCode.NO_NODE, "there isn't a node");
            }

            String fullUrl = serviceFileUrl(CLOUD_SCHEME, service, url, node.addr);
            DownloadCallback future = new DownloadCallback(node, req.traceId, saveAs);
            http.asyncGet(fullUrl, null, future);
            return future;
        }, Pool);
    }

    public static CompletableFuture<String> cloudFileUrl(ServiceReqBuilder req) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("cloudFileUrl({})", req);
        }
        req.cid(IConst.ROOT_COMPANY_ID);
        return cloudLookup(req).thenApplyAsync((node)-> {
            if(node == null) {//找不到，则直接失败
                LOG.error("Can't find {}'s node when cloudFileUrl {}", req.callee, req.url);
                return null;
            }
            String base = CLOUD_SCHEME + node.addr + '/' + req.callee;
            if (req.url.charAt(0) != '/') { //没有/api则加上它
                base += '/';
            }
            return base + req.url;
        }, Pool);
    }
    //-------------------------------------------------------------------------
    /**
     * 调用时，根据caller或轮询方式，确定被调用方的IP
     * @param req 请求体
     * @return 异步处理结果，符合要求的节点
     */
    private static CompletableFuture<NodeAddress> serviceLookup(ServiceReqBuilder req) {
        ServiceDNS serviceDns = (ServiceDNS) LocalDNS.get(req.callee);
        if (serviceDns != null) {
            /*
             * 只要不为空，则使用它，哪怕超期一点点，继续等待其他线程更新。
             * 如果一次更新失败，则隔一段时间后继续尝试更新，
             * 此种方法解决不了第一次启动时的浪涌问题。
             */
            if(!serviceDns.needUpdate()) {
                return CompletableFuture.completedFuture(serviceDns.lookup(req.nodeId()));
            }
        }

        //过期后，刷新一次，默认每隔300秒刷新，如果刷新失败，则先使用原先的
        if(LOG.isDebugEnabled()) {
            LOG.debug("serviceLookup(caller:{}, callee:{})", req.caller.name, req.callee);
        }

        ServiceReqBuilder builder = new ServiceReqBuilder(req.caller, SERVICE_BIOS)
                .url(BIOS_API_SERVICENODES)
                .appendPara("partId", Integer.toString(PartitionConfig.instance().partition))
                .appendPara("service", req.callee)
                .traceId(req.traceId)
                .appToken("*")
                .cid(req.cid);
        return BiosClient.get(builder).thenComposeAsync((hr)-> {
            if(hr.code != RetCode.OK || hr.data == null || hr.data.isEmpty()) {
                LOG.error("Fail to get nodes of {}->{} from bios, result:{}",
                        req.caller.name, req.callee, hr.brief());
                if(serviceDns != null) { //查询dns失败，则仍然使用老的数据
                    serviceDns.resetUpdate();
                }
                return CompletableFuture.completedFuture(null);
            }

            ServiceDNS dns = ServiceDNS.parse(req.callee, hr.data);
            if(dns != null) {
                setLocalDns(req.callee, dns);
                return CompletableFuture.completedFuture(dns.lookup(req.nodeId()));
            }
            return CompletableFuture.completedFuture(null);
        }, Pool);
    }

    /**
     * 调用时，根据caller或轮询方式，确定被调用方的IP
     * @param caller 调用方
     * @param callee 被调用
     * @param traceId 跟踪ID，用于输出到日志中，便于定位
     * @param nodeId 建议的节点id
     * @return 异步处理结果，符合要求的节点
     */
    private static CompletableFuture<NodeAddress> cloudLookup(String caller, String callee, String traceId, int nodeId) {
        ServiceDNS serviceDns = (ServiceDNS) CloudDNS.get(callee);
        if (serviceDns != null) {
            /*
             * 只要不为空，则使用它，哪怕超期一点点，继续等待其他线程更新。
             * 如果一次更新失败，则隔一段时间后继续尝试更新，
             * 此种方法解决不了第一次启动时的浪涌问题。
             */
            if(!serviceDns.needUpdate()) {
                return CompletableFuture.completedFuture(serviceDns.lookup(nodeId));
            }
        }

        //过期后，刷新一次，默认每隔300秒刷新，
        if(LOG.isDebugEnabled()) {
            LOG.debug("cloudlookup(caller:{}, callee:{})", caller, callee);
        }

        Map<String, String> headers = new HashMap<>();
        if(!StringUtil.isEmpty(traceId)) {
            headers.put(IConst.HEAD_TRACE_ID, traceId);
        }
        //即使多个公司共用服务器，用任何一个公司都可以获取，因为它们的dns是一样的
        CompanyInfo ci = CompanyInfo.instance();
        String cid = Integer.toString(ci.id);
        headers.put(IConst.HEAD_CID, cid);
        headers.put(IConst.HEAD_ACCESS_TOKEN, ci.accessCode());

        //httpDns一定存在，因为初始化时已加入
        ServiceDNS httpDns = (ServiceDNS) CloudDNS.get(IConst.SERVICE_HTTPDNS);
        NodeAddress node = httpDns.lookup(ci.id); //http dns只有一个，并且用的是域名
        if(node == null) {
            LOG.error("Fail to get {}'s node", IConst.SERVICE_HTTPDNS);
            return CompletableFuture.completedFuture(null);
        }
        String fullUrl = serviceApiUrl(CLOUD_SCHEME, IConst.SERVICE_HTTPDNS,
                "/lookup?service=" + callee + "&id=" + cid, node.addr);

        return getFrom(node, fullUrl, headers, traceId).thenComposeAsync((hr)-> {
            List<String> nodes;
            if(hr.code != RetCode.OK || hr.data == null || hr.data.isEmpty()
               || (nodes = ValParser.getAsStrList(hr.data, "addrs")) == null) {
                LOG.error("Fail to get nodes of {} from {}({}), result:{}",
                        callee, IConst.SERVICE_HTTPDNS, node.addr, hr.code);
                if(serviceDns != null) {
                    serviceDns.resetUpdate(); //获取失败，只能用老的
                    return CompletableFuture.completedFuture(serviceDns.lookup(nodeId));
                }
                return CompletableFuture.completedFuture(null);
            }
            ServiceDNS dns = ServiceDNS.create(callee, nodes.toArray(new String[]{}));
            CloudDNS.put(callee, dns);
            return CompletableFuture.completedFuture(dns.lookup(nodeId));
        }, Pool);
    }

    private static CompletableFuture<NodeAddress> cloudLookup(ServiceReqBuilder req) {
       return cloudLookup(req.caller.name, req.callee, req.traceId, req.nodeId());
    }

    public static void setLocalDns(String service, AbstractDNS dns) {
        if(dns == null) {
            LOG.error("Invalid dns({}) was set", service);
            return;
        }
        LOG.debug("setLocalDns({},{}->{})", service, dns.name(), dns);
        LocalDNS.put(service, dns);
    }

    /**
     * 当网络状态变化后，本地IP可能已经发生了变化，服务器会重新启动，此时必须清除原有的dns
     * 永久有效的dns记录不会被清除
     */
    public static void clearLocalDns() {
        LOG.debug("clearLocalDns");
        List<String> clearList = new ArrayList<>();
        for(Map.Entry<String, AbstractDNS> o : LocalDNS.entrySet()) {
            if(o.getValue().expiresAt() > 0) { //永久有效期的不能删除，比如JsHttp中设置的解析
                clearList.add(o.getKey());
            }
        }

        for(String k : clearList) {
            LocalDNS.remove(k);
        }
    }

    /**
     * 公有云服务解析，添加httpdns服务的地址，只有它需要提供域名，永不过期。
     * httpdns默认使用域名，域名本身的多实例依赖LB或DNS的A地址+D监控
     * 本地环境中，服务端查询服务地址使用的是bios的lookup，与公有云不同
     * @param name 名称
     * @param addrs 地址
     * @param expiresIn 超时时间，单位秒
     */
    public static void setCloudDns(String name, String[] addrs, int expiresIn) {
        ServiceDNS sd = ServiceDNS.create(name, addrs, expiresIn);
        CloudDNS.put(name, sd);
    }
    //-------------------------------------------------------------------------
    /**
     * 向oAuth2服务发起请求，获取服务间互调时的token（不是终端用户与服务端之间的token）
     * 调用方获取token时，caller就是本方服务名，callee是被调方服务名，
     * 应用用自己的私钥签名secret，在oAuth2中从bios中获取对应的公钥，验证secret是否正确，
     * 然后通过codebook生成一个token，
     * 在verify时，通过codebook即可检验token是否合法，无需查询数据库。
     *
     * @param req 请求体
     * @param tokenType 需要的token类型
     * @param scope 资源范围，服务名或者db名称，要带前缀，防止callee与db重名
     * @return 服务间访问令牌，其中的cid已替换成参数中的
     */
    public static CompletableFuture<String> serviceToken(ServiceReqBuilder req, int tokenType, String scope) {
        long tokenCacheId = AccessToken.hashCode(tokenType,
                PartitionConfig.instance().partition, req.caller.name, scope);

        AccessToken accessToken = ServiceTokenCache.get(tokenCacheId);
        if(accessToken != null) {
            if(!accessToken.expired()) {
                //提前一点，让一个线程更新token，避免过期时多个线程同时涌入
                if(!accessToken.needUpdate()) {
                    // 替换真实的cid，在被调方用于决定使用哪个数据库
                    // 存在安全风险，恶意篡改cid，但都是服务间调用，基本可控
                    return CompletableFuture.completedFuture(accessToken.generate());
                }
            }
        }

        if(LOG.isDebugEnabled()) {
            LOG.debug("serviceToken(traceId:{},cid:{},caller:{},scope:{},tokenType:{},cacheId:{})",
                    req.traceId, req.cid, req.caller.name,
                    scope, TOKENTYPE_NAMES[tokenType], tokenCacheId);
        }
        /*
         * 用应用的私钥生成key，oAuth端需要从bios中获得应用的公钥验签。
         * 将参数准备的工作放在supply中，是为了尽量减少等待的时间
         * 从此处开始，一直到本函数的末尾，其他线程仍可能在这里插队，产生重复的请求。
         * 在请求token的场景中，重复请求影响不大，所以这里没有使用ConcurrentHashMap.putIfAbsent。
         * 使用putIfAbsent没有用，因为CompletableFuture一旦创建就会执行，不等待调用run就自动run了。
         */
        String tokenKey = req.caller.name + SERVICE_SEPERATOR_C + scope;
        byte[] sign = req.caller.tokenWorker.sign(tokenKey.getBytes(DEFAULT_CHARSET)); //耗时操作
        String key = ByteUtil.bin2base64(sign);

        UrlPathInfo url = new UrlPathInfo("auth");
        url.appendPara("grant_type", "client_credentials", false);
        url.appendPara("scope", scope, false);
        url.appendPara("client_id", req.caller.name, false);
        url.appendPara("client_secret", key, false);

        ServiceReqBuilder oauthReq = new ServiceReqBuilder(req.caller, SERVICE_OAUTH2)
                .url(url.toString())
                .traceId(req.traceId)
                .cid(req.cid);
        return ServiceClient.getPublic(oauthReq).thenApplyAsync((hr) -> {
            if(hr.code != RetCode.OK || hr.data == null) {
                LOG.error("Invalid {} result:{}", SERVICE_OAUTH2, hr.brief());
                if(accessToken != null) {
                    //更新缓存中的更新标志，让下一个请求再次执行签名逻辑，尽管token不一定已经过期
                    accessToken.resetUpdate();
                }
                return null;
            }

            /*
             * 响应包括access_token、expires_at、token_type三个字段，
             * 不支持refresh_token
             */
            String token = ValParser.getAsStr(hr.data, IOAuth.SEG_ACCESS_TOKEN);
            //被调方到oAuth上验签，所以无需本地验签
            AccessToken at = AccessToken.parse(token, ITokenWorker.NullTokenWorker);
            if(at == null) {
                LOG.error("Fail to parse token {}", token);
                return null;
            }
            ServiceTokenCache.put(tokenCacheId, at);
            if(LOG.isDebugEnabled()) {
                LOG.debug("AccessToken(traceId:{},cid:{},caller:{},scope:{},token:{},expiresAt:{},now:{})",
                        req.traceId, req.cid, req.caller.name, scope,
                        token, at.expiresAt(), System.currentTimeMillis());
            }
            return token;
        }, Pool);
    }

    public static CompletableFuture<String> serviceToken(ServiceReqBuilder req) {
        return serviceToken(req, TOKENTYPE_SERVICE, SERVICE_CALLEE_HEAD + req.callee);
    }

    public static CompletableFuture<String> getCdnServer(String service, int area, int cid) {
        int size = CdnList.size();
        if(size > 0 && System.currentTimeMillis() - cdnCacheTime < CDN_CACHE_TIME) {
            return CompletableFuture.completedFuture(CdnList.get(SecureUtil.getRandom().nextInt(size)));
        }
        ServiceReqBuilder req = backendReqBuilder(IConst.SERVICE_APPSTORE)
                .url("/api/cdn/list?service=" + service + "&area=" + area)
                .cid(cid)
                .traceId("company_" + cid);
        return cloudGet(req).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.warn("Fail to get cdns in {}, result:{}", service, hr.brief());
                return null;
            }
            
            List<String> list = ValParser.getAsStrList(hr.data, "cdns");
            if(list != null && !list.isEmpty()) {
                ServiceClient.CdnList.clear();
                ServiceClient.CdnList.addAll(list);
                ServiceClient.cdnCacheTime = System.currentTimeMillis();
                int no = SecureUtil.getRandom().nextInt(list.size());
                return list.get(no);
            }
            
            return null;
        }, Pool);
    }
}