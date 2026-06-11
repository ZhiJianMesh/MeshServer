package cn.net.zhijian.mesh.frm.service.httpdns;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;

/**
 * 在私有云中，用于客户端查询所有服务的地址
 * 如果请求来自公网，则返回外网网关的地址，否则返回内网地址
 * @author flyinmind of csdn.net
 * `/httpdns/api/probe`
 *
 */
public class DnsProbe extends HttpDnsBase {
    public DnsProbe(ServiceInfo serviceInfo, ApiInfo apiInfo, IServiceServer server) {
        super(serviceInfo, apiInfo, "dnsprobe", server);
    }

    @Override
    public CompletableFuture<HandleResult> handle1(AbsServerRequest req, Map<String, Object> resp) {
        CompanyInfo localCompany = CompanyInfo.instance();

        return getRouteConfig(localCompany).thenApplyAsync(rc -> {
            if(rc == null) {
                return new HandleResult(RetCode.NOT_EXISTS, "no routes");
            }
            List<String[]> addrs = rc.probe(req.isExternal());
            if(addrs == null) {
                return new HandleResult(RetCode.NOT_EXISTS, "no routes");
            }
            
            return new HandleResult(Map.of("addrs", addrs, "name", localCompany.name()));
        });
    }
}
