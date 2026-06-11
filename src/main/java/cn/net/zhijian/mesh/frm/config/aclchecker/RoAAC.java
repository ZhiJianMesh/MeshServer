package cn.net.zhijian.mesh.frm.config.aclchecker;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ProcessInfo;

/**
 * 基于角色或者基于属性的权限控制
 */
class RoAAC extends RBAC {
    private final ProcessInfo abacChecker;
    
    RoAAC(ProcessInfo abacChecker) {
        super(CHECKER_ROAAC);
        this.abacChecker = abacChecker;
    }

    @Override
    public CompletableFuture<HandleResult> check(ApiInfo ai,
            AbsServerRequest req, Map<String, Object> respData) {
        return super.check(ai, req, respData).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) { //rbac通不过，才执行abac
                return abacChecker.handle(req, respData, Pool);
            }
            return CompletableFuture.completedFuture(hr);
        }, Pool);
    }
    
    @Override
    public void destroy() {
        super.destroy();
        abacChecker.destroy();
    }
}
