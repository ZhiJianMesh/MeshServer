package cn.net.zhijian.mesh.builtin.user;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;

/**
 * 退出登录
 * 相同userid，分发的实例与verify实例一致
 * @author flyinmind of csdn.net
 *
 */
public final class Logout extends UserBase {
    public Logout(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
        apiInfo.addExtPara(RESP_EXPIRESAT);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        AccessToken at = req.token();
        req.put(RESP_EXPIRESAT, at.expiresAt()); //限制过期时间，避免多端误删
        long cacheId = getCacheId(req.cid(), at.generate());
        AccessToken cacheToken = UserTokenCache.get(cacheId);
        if(cacheToken.expiresAt() == at.expiresAt()) {
            //删除缓存中的token，gw需要按tokenCaller分发，否则不能及时删除掉
            UserTokenCache.remove(cacheId);
        }
        //删除数据库中的token
        return super.handle(req, resp);
    }
}