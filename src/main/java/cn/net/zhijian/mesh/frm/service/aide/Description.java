package cn.net.zhijian.mesh.frm.service.aide;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.JsonUtil;

/**
 * 在设置RBAC时，需要了解服务中所有接口的信息
 * @author flyinmind of csdn.net
 *
 */
public class Description extends AbsProcessor {
    public Description(ServiceInfo si, ApiInfo apiInfo) {
        super(si, apiInfo, API_SERVICE_INFO);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        ServiceInfo si = req.serviceInfo();
        File cfgFile = new File(FileUtil.addPath(si.homeDir, SERVICE_CONFIG_FILE));
        if(!cfgFile.exists()) {
            return HandleResult.future(RetCode.NOT_EXISTS, "service config not exists");
        }
        Map<String, Object> data = JsonUtil.jsonFileToMap(cfgFile, true);
        if(data == null) {
            return HandleResult.future(RetCode.DATA_WRONG, "invalid config format");
        }
        return HandleResult.future(data);
    }
}