package cn.net.zhijian.mesh.builtin.keystore;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
/**
 * 获取根环境公司的数据根密钥，只有在ROOT模式才会调用，在接口/company/putcdr、/company/getcdr中使用。
 * 公司私有环境中用/keypair/putIfAbsent、/keypair/put、/keypair/get等
 */
public class GetCDR extends AbsProcessor {
    public GetCDR(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }
    
    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        return KeyStoreBase.getDataRootKey(req.cid(), req.serviceInfo()).thenComposeAsync(kp -> {
            if(kp == null) {
                return futureResult(RetCode.NOT_EXISTS, "no root key");
            }
            return futureResult(Map.of("kp", kp.toString()));
        }, Pool);
    }
}
