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
public class FileList extends AideBase {
    public FileList(IServiceServer server, ServiceInfo si, ApiInfo apiInfo) {
        super(server, si, apiInfo, API_SERVICE_FILELIST);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        List<String> files = server.files(serviceInfo.name);
        Map<String, Object> data = new HashMap<>();
        data.put("service", serviceInfo.name);
        data.put("number", files.size());
        data.put("files", files);
        return HandleResult.future(data);
    }
}