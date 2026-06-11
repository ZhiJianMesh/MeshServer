package cn.net.zhijian.mesh.frm.service.aide;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;

/**
 * 用于调用公有云数据库始化接口，比如CRM的工作流初始化接口，此类接口的tokenChecker应为APP。
 * 接口实现要求可重入，且接口只接受OMKey认证，接口名称以"__"开头，只接受get方法。
 * 初始化调用是异步执行的，不影响启动的成败。
 * 云上调用时，只能在OM服务中调用，因为只有OM具有OM的私钥
 * 如果需要区分公司，则需要传入cid头部。
 * @author flyinmind of csdn.net
 *
 */
public class InitDb extends AideBase {
    public InitDb(IServiceServer server, ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(server, serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        ServiceInfo si = serviceInfo();
        return si.initWebDb(req.cid());
    }
}