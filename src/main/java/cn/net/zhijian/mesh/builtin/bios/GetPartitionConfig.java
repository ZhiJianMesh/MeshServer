package cn.net.zhijian.mesh.builtin.bios;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.mesh.frm.service.backend.BackendBase;
import cn.net.zhijian.util.MapBuilder;

/**
 * 接受其他实例的请求，获得parttition.cfg中的内容
 * 此接口为公开接口，parttition.cfg中无敏感信息，任意请求都可以查询
 * @author flyinmind of csdn.net
 *
 */
class GetPartitionConfig extends BackendBase {
    public GetPartitionConfig(IServiceServer vServer, ServiceInfo si, ApiInfo apiInfo, String name) {
        super(vServer, si, apiInfo, name);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        PartitionConfig partCfg = PartitionConfig.instance();
        Map<String, Object> data = MapBuilder.of(
                "cfg", partCfg.toString(),
                "main", partCfg.biosDNS().mainAddr());
        return HandleResult.future(data);
    }
}
