package cn.net.zhijian.mesh.frm.service.aide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.MapBuilder;

/**
 * 在加载服务时，系统自动为每个服务都增加了此接口的实现。
 * 用于给应用的客户端提供与当前服务端配合的端侧信息，便于端侧在启动时及时升级
 * @author flyinmind of csdn.net
 *
 */
public class ClientInfo extends AbsProcessor {
    private final HandleResult result;
    
    public ClientInfo(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
        if(serviceInfo.clientType != ServiceInfo.ClientType.NONE) {
            Map<String, Object> appInfo = new HashMap<>();
            appInfo.put("name", serviceInfo.name);
            appInfo.put("displayName", serviceInfo.displayName);
            appInfo.put("author", serviceInfo.author);
            appInfo.put("level", serviceInfo.level);
            appInfo.put("type", serviceInfo.type.ordinal());
            appInfo.put("version", serviceInfo.version);

            List<Map<String, Object>> dependencies = new ArrayList<>();
            for(ServiceInfo.Dependency dp : serviceInfo.dependencies) {
                if(!dp.client) {
                    continue;
                }
                dependencies.add(MapBuilder.of("name", dp.name, "minVer", dp.minVersion));
            }
            appInfo.put("dependencies", dependencies);
            this.result = new HandleResult(RetCode.OK, appInfo);
        } else {
            this.result = new HandleResult(RetCode.NOT_SUPPORTED_FUNCTION);
        }
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        return CompletableFuture.completedFuture(result);
    }
}