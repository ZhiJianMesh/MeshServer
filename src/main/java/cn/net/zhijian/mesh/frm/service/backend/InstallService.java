package cn.net.zhijian.mesh.frm.service.backend;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.para.ParameterInfo;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;

/**
 * 运维平台调用此接口，在运行时下载安装并启用服务
 * @author flyinmind of csdn.net
 *
 */
final class InstallService extends BackendBase {
    public InstallService(IServiceServer vServer, ServiceInfo si, ApiInfo apiInfo, String name) {
        super(vServer, si, apiInfo, name);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String service = req.getString("service");
        String pwd = req.getString("pwd");
        return server.install(service, pwd);
    }

    @Override
    public RequestInfo getRequestInfo() {
        return new RequestInfo(new ParameterInfo[] {
            new ParameterInfo.Builder(PARA_SERVICE, ParameterInfo.TYPE_STRING)
                .setRegular("^[a-zA-Z0-9_]{1,30}$").build()
        });
    }
}
