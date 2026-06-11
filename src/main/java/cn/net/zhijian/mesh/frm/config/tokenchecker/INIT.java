package cn.net.zhijian.mesh.frm.config.tokenchecker;

import java.util.concurrent.CompletableFuture;
import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsTokenChecker;

/**
 * 既可以用OM签名访问，也可以用应用的私钥签名访问，
 * 同时可以使用后台维护权限访问，用公司私钥签名，或用公司临时维护密码签名访问
 */
class INIT extends AbsTokenChecker {
    final APP appTokenChecker;
    final APP omTokenChecker;
    final BACKEND backendTokenChecker;
    
    INIT(APP app, APP om, BACKEND backend) {
        super(INIT_TOKEN_CHECKER);
        this.appTokenChecker = app;
        this.omTokenChecker = om;
        this.backendTokenChecker = backend;
    }

    @Override
    public CompletableFuture<AccessToken> check(AbsServerRequest req, String token) {
        int signType = AccessToken.getSignType(token);
        //支持app(服务自己调用自己的接口),company,pwd三种
        if(signType == SIGNTYPE_APPKEY) {
            return appTokenChecker.check(req, token);
        }
        if(signType == SIGNTYPE_OMKEY) {
            return omTokenChecker.check(req, token);
        }
        return backendTokenChecker.check(req, token);
    }
}
