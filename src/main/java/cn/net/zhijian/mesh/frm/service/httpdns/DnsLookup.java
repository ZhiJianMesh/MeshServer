package cn.net.zhijian.mesh.frm.service.httpdns;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 在私有云中，端侧查询单个服务的ip及端口
 * @author flyinmind of csdn.net
 * `/httpdns/api/lookup`
 *
 */
public class DnsLookup extends HttpDnsBase {
    private static final Logger LOG = LogUtil.getInstance();
    
    public DnsLookup(ServiceInfo serviceInfo, ApiInfo apiInfo, IServiceServer server) {
        super(serviceInfo, apiInfo, "lookup", server);
    }

    @Override
    public CompletableFuture<HandleResult> handle1(AbsServerRequest req, Map<String, Object> resp) {
        CompanyInfo localCompany = CompanyInfo.instance();
        
        String service = ValParser.getAsStr(req.params(), "service");
        return getRouteConfig(localCompany).thenComposeAsync(rc -> {
            String[] addrs = rc.lookup(service, req.isExternal());
            if(addrs != null) { //服务存在于本地
                return futureResult(Map.of("addrs", addrs));
            }
    
            //服务在本地没有，可能在至简网格中
            ServiceReqBuilder builder = ServiceClient.backendReqBuilder(IConst.SERVICE_HTTPDNS)
                    .url("/lookup?service=" + service + "&id=" + localCompany.id)
                    .cid(localCompany.id)
                    .nodeId(localCompany.id);
            return ServiceClient.cloudGet(builder).thenApplyAsync(hr -> {
                if(hr.code == RetCode.OK) {
                    hr.data.put("cloud", true); //标识为cloud服务，cloud服务的token需要用公司私钥签名
                } else {
                    LOG.error("Fail to lookup `{}` from cloud,result:{}", service, hr.brief());
                }
                return hr;
            }, Pool);
        }, Pool);
    }
}
