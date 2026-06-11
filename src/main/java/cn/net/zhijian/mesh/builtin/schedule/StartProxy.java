package cn.net.zhijian.mesh.builtin.schedule;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.process.RDBProcessor;
import cn.net.zhijian.util.UrlPathInfo;

/**
 * 其他服务委托schedule发起一个调用
 * 使用它的好处就是如果发生失败，会先缓存起来，然后会尝试多次
 * 直到成功，或尝试次数用完
 * 被委托调用的接口的tokenChecker必须为APP-*或APP-schedule
 * @author flyinmind of csdn.net
 */
public final class StartProxy extends RDBProcessor {
    public StartProxy(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
        apiInfo.addExtPara(IConst.EMBEDED_CODE);
        apiInfo.addExtPara(IConst.EMBEDED_INFO);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String url = req.getString("url");
        String service = req.getString(IConst.EMBEDED_TOKEN_CALLER);
        int cid = req.cid();

        String trace = "proxy_" + cid;
        ServiceReqBuilder reqBuilder = new ServiceReqBuilder(req.serviceInfo(), service);
        UrlPathInfo taskUrl = new UrlPathInfo(url);
        reqBuilder.url(taskUrl.toString())
           .cid(cid)
           .traceId(trace)
           .appToken("*"); //回调接口tokenChecker必须为APP-schedule或APP-*
        return ServiceClient.getPublic(reqBuilder).thenComposeAsync(hr -> {
            if(hr.code == RetCode.OK) { //执行成功，则立即结束
                return CompletableFuture.completedFuture(hr);
            }
            req.put(IConst.EMBEDED_CODE, hr.code);
            req.put(IConst.EMBEDED_INFO, hr.info);
            //否则先缓存任务，后面多次尝试
            return super.handle(req, resp);
        }, Pool);        
    }
}