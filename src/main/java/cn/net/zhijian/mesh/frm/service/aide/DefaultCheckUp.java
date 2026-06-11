package cn.net.zhijian.mesh.frm.service.aide;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;

/**
 * 默认的checkup实现
 * @author flyinmind of csdn.net
 *
 */
public class DefaultCheckUp extends AbsProcessor {
    public DefaultCheckUp(ServiceInfo serviceInfo, ApiInfo apiInfo) {
        super(serviceInfo, apiInfo, "check_up");
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        resp.put("cpuTime", serviceInfo().cpuTime);
        resp.put("reqTimes", req.getStat().incApis(0));
        return futureResult(resp);
    }
}
