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
 * 运维平台调用此接口，在运行时注销一个服务，并不会删除服务相关的配置文件
 * @author flyinmind of csdn.net
 *
 */
final class UnInstallService extends BackendBase {
    public UnInstallService(IServiceServer vServer, ServiceInfo si, ApiInfo apiInfo, String name) {
        super(vServer, si, apiInfo, name);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String service = req.getString("service");
        String pwd = req.getString("pwd");
        return server.unInstall(service, pwd);
    }

    @Override
    public RequestInfo getRequestInfo() {
        ParameterInfo name = new ParameterInfo.Builder(PARA_SERVICE, ParameterInfo.TYPE_STRING)
                .setRegular("^[a-zA-Z0-9_]{1,30}$").build();
        return new RequestInfo(new ParameterInfo[] {name});
    }
}
