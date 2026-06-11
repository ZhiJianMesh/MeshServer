package cn.net.zhijian.mesh.builtin.oauth2;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;

/**
 * oauth是一个特殊服务，在bios中不会出现。
 * 因为它用的数据库就是bios，任何服务，只要在bios中注册过，都可以调用它获得access_token。
 * @author flyinmind of csdn.net
 */
public final class Verify extends AuthBase {
    private static final Logger LOG = LogUtil.getInstance();
    
    public Verify(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }
    
    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String token = req.getString(SEG_ACCESS_TOKEN);
        PartitionConfig partCfg = PartitionConfig.instance();
        if(!partCfg.isPartitionValid(token)) {
            LOG.error("Request from a invalid partition,!={}", partCfg.partition);
            return futureResult(RetCode.NO_RIGHT);
        }
        
        AccessToken at = AccessToken.parse(token, codebookTokenWorker);
        if(at == null || at.expired()) {
            return futureResult(RetCode.NO_RIGHT);
        }
        
        return futureResult();
   }
}