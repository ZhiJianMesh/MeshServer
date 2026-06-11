package cn.net.zhijian.mesh.frm.service.aide;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;

/**
 * 在设置RBAC时，需要了解服务中所有接口的信息
 * @author flyinmind of csdn.net
 *
 */
public class ApiList extends AideBase {
    public ApiList(IServiceServer vServer, ServiceInfo si, ApiInfo apiInfo) {
        super(vServer, si, apiInfo, API_SERVICE_APILIST);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        Map<String, Object> data = new HashMap<>();
        data.put("service", serviceInfo.name);
        List<Map<String, String>> apis = server.apis(serviceInfo.name);
        data.put("number", apis.size());
        data.put("apis", apis);
        return HandleResult.future(data);
    }
}