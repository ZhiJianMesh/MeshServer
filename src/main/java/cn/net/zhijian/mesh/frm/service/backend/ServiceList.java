package cn.net.zhijian.mesh.frm.service.backend;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;

/**
 * 返回本实列上的服务列表。
 * 运维平台调用此接口获得节点上有哪些服务，以及它们的版本信息
 * @author flyinmind of csdn.net
 *
 */
final class ServiceList extends AbsProcessor {
    private final IServiceServer server;

    public ServiceList(IServiceServer server, ServiceInfo si, ApiInfo apiInfo, String name) {
        super(si, apiInfo, name);
        this.server = server;
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        Map<String, Object> data  = new HashMap<>();
        for(Map.Entry<String, ServiceInfo> e : this.server.services().entrySet()) {
            data.put(e.getKey(), e.getValue().version());
        }

        return futureResult(data);
    }
}
