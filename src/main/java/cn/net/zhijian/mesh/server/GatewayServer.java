package cn.net.zhijian.mesh.server;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.ProxyResponse;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsFileMethod;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IServiceWatcher;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.frm.method.ApiMethod;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * http网关服务器
 * 如果服务器作为网关服务器运行，则不能当作tcp或http服务器运行
 */
public class GatewayServer extends ServiceServer implements IThreadPool {
    private static final Logger LOG = LogUtil.getInstance();
       
    //在请求bios获取服务位置时用到，bios./service/nodes接口是BIOS认证
    private final ServiceInfo gateway;

    /**
     * 
     * @param workDir 运行根目录
     * @param omPwd om密码，用于从bios中查询服务的密钥对
     * @throws MeshException 异常
     */
    public GatewayServer(String workDir, String omPwd) throws MeshException {
        super(workDir);
        
        this.gateway = ServiceInfo.createBuiltIn(
                ServiceInfo.serviceHome(SERVICE_GATEWAY),
                SERVICE_GATEWAY,
                null,
                null,
                IServiceWatcher.DefaultWatcher);
        
        Map<String, String> kps = readKeyPairs();
        
        try {
            String sKp = kps.get(SERVICE_GATEWAY);
            EccKeyPair ekp = null;
            if(!StringUtil.isEmpty(sKp)) {
                try {
                    ekp = EccKeyPair.parse(sKp);
                } catch (Exception e) {
                    LOG.error("Fail to parse {}'s keypair", SERVICE_GATEWAY, e);
                    throw new MeshException("Invalid service key pair of " + SERVICE_GATEWAY, e);
                }
            }
            
            if(ekp == null) {
                //本地没有，则从主bios获取，所以gateway启动前需要保证主bios已启动
                ekp = IServiceWatcher.DefaultWatcher.getKey(gateway, omPwd).get(5, TimeUnit.SECONDS);
                if(ekp == null) {
                    throw new MeshException("invalid service key pair of " + SERVICE_GATEWAY);
                }
            }
            this.gateway.tokenWorker.setKey(ekp);
        } catch (Exception e) {
            throw new MeshException("fail to get service key pair of " + SERVICE_GATEWAY, e);
        }
    }

    @Override
    public void fileExecute(AbsServerRequest req) {
        AbsFileMethod afm = getFile(req.uri);
        if(afm != null) {
            super.fileExecute(req);
            return;
        }
        
        AbsServerResponse resp = req.createResponse();
        req.setServiceInfo(gateway);
        String uri = req.uri;
        int pos = uri.indexOf('/', 1); // `/callee/api/...`
        String callee = uri.substring(1, pos);

        ServiceReqBuilder callBuilder = new ServiceReqBuilder(gateway, callee);
        try {
            getFile(req, callBuilder).whenCompleteAsync((r, e) -> {
                if(e != null) {
                    resp.setStatus(HttpResponseStatus.NOT_FOUND, RetCode.INTERNAL_ERROR);
                    if(LOG.isDebugEnabled()) {
                        LOG.error("Fail to get {}", req.uri, e);
                    } else { //减少打印
                        LOG.error("Fail to get {},error:{}", req.uri, e.getMessage());
                    }
                } else if(r == null) {
                    resp.setStatus(HttpResponseStatus.NOT_FOUND, RetCode.INTERNAL_ERROR);
                } else {
                    resp.putHeaders(r.headers);
                    resp.end(r.body, 0, r.body.length);
                }
            }, Pool);
        } catch (Exception e) {
            resp.end(HandleResult.InternalError);
            LOG.error("Fail to execute {}, times:{}", req.uri, e);
        }
    }

    @Override
    public void apiExecute(AbsServerRequest req) {
        ApiMethod am = getApi(req.uri);
        if(am != null) {
            super.apiExecute(req);
            return;
        }
        
        AbsServerResponse resp = req.createResponse();
        req.setServiceInfo(gateway);
        String uri = req.uri;
        int pos = uri.indexOf('/', 1); // `/callee/api/...`
        String callee = uri.substring(1, pos);

        ServiceReqBuilder callBuilder = new ServiceReqBuilder(gateway, callee);
        try {
            transmit(req, callBuilder).whenCompleteAsync((hr, e) -> {
                if(e != null) {
                    resp.end(HandleResult.InternalError);
                    req.getStat().incExceptions(1);
                    if(LOG.isDebugEnabled()) {
                        LOG.error("Fail to call {}", req.uri, e);
                    } else { //减少打印
                        LOG.error("Fail to call {},error:{}", req.uri, e.getMessage());
                    }
                } else {
                    resp.end(hr);
                }
            }, Pool);
        } catch (Exception e) {
            resp.end(HandleResult.InternalError);
            LOG.error("Fail to execute {}, times:{}", req.uri, e);
        }
    }
    
    private CompletableFuture<HandleResult> transmit(AbsServerRequest req, ServiceReqBuilder builder) {
        builder.headers(req.headers()); //直接透传所有头部
        if(req.method.equals(IConst.METHOD_POST)) {
            return ServiceClient.servicePost(builder.url(req.uri).body(new String(req.body(), IConst.DEFAULT_CHARSET)));
        }
        if(req.method.equals(IConst.METHOD_PUT)) {
            return ServiceClient.servicePut(builder.url(req.uri).body(new String(req.body(), IConst.DEFAULT_CHARSET)));
        }

        UrlPathInfo urlInfo = new UrlPathInfo(req.uri);
        urlInfo.appendParas(req.params());
        builder.url(urlInfo.toString());
        if(req.method.equals(IConst.METHOD_DELETE)) {
            return ServiceClient.serviceDelete(builder);
        }
        return ServiceClient.serviceGet(builder);
    }
    
    private CompletableFuture<ProxyResponse> getFile(AbsServerRequest req, ServiceReqBuilder builder) {
        builder.headers(req.headers()); //直接透传所有头部
        UrlPathInfo urlInfo = new UrlPathInfo(req.uri);
        urlInfo.appendParas(req.params());
        builder.url(urlInfo.toString());
        return ServiceClient.fileGet(builder);
    }
}
