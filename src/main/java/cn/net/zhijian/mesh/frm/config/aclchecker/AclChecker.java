package cn.net.zhijian.mesh.frm.config.aclchecker;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ProcessInfo;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.LogUtil;

/**
 * 检查用户是否具有对某类或某行数据具有操作权限。
 * 服务间的调用不涉及用户级的鉴权，
 * 所以只有tokenChecker为USER的情况下，processor的aclChecker才有意义。
 * @author flyinmind of csdn.net
 *
 */
public abstract class AclChecker implements IThreadPool, IOAuth {
    private static final Logger LOG = LogUtil.getInstance();

    protected static final String CHECKER_RBAC = "RBAC";
    protected static final String CHECKER_ROAAC = "ROAAC";
    protected static final String CHECKER_RAAAC = "RAAAC";
    protected static final String CHECKER_ABAC = "ABAC";
    
    public final String name;
    
    public abstract CompletableFuture<HandleResult> check(ApiInfo ai,
            AbsServerRequest req, Map<String, Object> respData);
    public abstract void destroy();

    public AclChecker(String name) {
        this.name = name;
    }
    
    public boolean checkParameter(ApiInfo ai, boolean isUserToken) {
        /*
         * 必须在tokenChecker为USER的接口定义中才可以使用ACL控制。
         * 此为默认实现，如果需要更改此规则，请重载实现AbstractAclChecker
         */
        if(!isUserToken) {
            LOG.error("tokenChecker must be {},{},{} or {}, when using aclChecker in `{}`",
                    USER_TOKEN_CHECKER, BASEUSER_TOKEN_CHECKER,
                    UNIUSER_TOKEN_CHECKER, COMPANY_TOKEN_CHECKER,
                    ai.url);
            return false;
        }
        return true;
    }

    public static AclChecker getChecker(String name, ProcessInfo abacChecker) {
        String uName = name.toUpperCase();
        if(uName.equals(CHECKER_RBAC)) {
            return RBAC.Instance;
        }
        
        if(abacChecker == null) {
            LOG.error("Invalid aclChecker config {}, no aclProcess", name);
            return null;
        }
        
        if(uName.equals(CHECKER_ROAAC)) {
            return new RoAAC(abacChecker);
        }
        if(uName.equals(CHECKER_RAAAC)) {
            return new RaAAC(abacChecker);
        }
        if(uName.equals(CHECKER_ABAC)) {
            return new ABAC(abacChecker);
        }
        LOG.error("Invalid aclChecker config,{}", name);
        return null;
    }    
}