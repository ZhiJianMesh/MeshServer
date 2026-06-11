package cn.net.zhijian.mesh.frm.service.backend;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.para.ParameterInfo;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.util.ValParser;

/**
 * 接受OM的请求，更新节点中的Bios服务节点信息
 * @author flyinmind of csdn.net
 *
 */
final class NotifyBiosSrvs extends BackendBase {
    public NotifyBiosSrvs(IServiceServer vServer, ServiceInfo si, ApiInfo apiInfo, String name) {
        super(vServer, si, apiInfo, name);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        List<String> bios = ValParser.getAsStrList(resp, "list");
        String main = ValParser.getAsStr(resp, "main");
        PartitionConfig partCfg = PartitionConfig.instance();
        try {
            if(partCfg.updateBiosDns(bios, main, true)) {
                return HandleResult.future();
            }
        } catch (MeshException e) {
            return HandleResult.future(RetCode.WRONG_PARAMETER, "invalid request data");
        }
        return HandleResult.future(RetCode.WRONG_PARAMETER);
    }

    @Override
    public RequestInfo getRequestInfo() {
        ParameterInfo addrs = new ParameterInfo.Builder("addrs", ParameterInfo.TYPE_STRING)
                .setRegular("^(\\d{1,3}\\.){3}\\d{1,3}:\\d{4,5}$").asList().build();
        return new RequestInfo(new ParameterInfo[] {addrs});
    }
}
