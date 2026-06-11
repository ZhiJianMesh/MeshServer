package cn.net.zhijian.mesh.frm.config.aclchecker;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ProcessInfo;

/**
 * 基于角色并且基于属性的权限控制
 */
class ABAC extends AclChecker {
    private final ProcessInfo abacChecker;
    
    ABAC(ProcessInfo abacChecker) {
        super(CHECKER_ABAC);
        this.abacChecker = abacChecker;
    }

    @Override
    public CompletableFuture<HandleResult> check(ApiInfo ai,
            AbsServerRequest req, Map<String, Object> respData) {
        return abacChecker.handle(req, respData, Pool);
    }

    @Override
    public void destroy() {
        abacChecker.destroy();
    }
}
