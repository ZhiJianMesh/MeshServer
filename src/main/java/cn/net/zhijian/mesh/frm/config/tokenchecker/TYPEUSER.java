package cn.net.zhijian.mesh.frm.config.tokenchecker;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 智能体客户端验证，与普通用户验证相同，通过调用/user/verify实现token校验。
 * 在校验过后，需要确认用户的type是否正确，但是不检查外网权限
 * 
 * @author flyinmind of csdn.net
 *
 */
class TYPEUSER extends BASEUSER {
    private static final Logger LOG = LogUtil.getInstance();
    //数量不会太多，设置为ai智能体后不会修改，也不会被泛洪攻击，所以直接用Map
    protected static final Map<String, String> AgentCache = new ConcurrentHashMap<>();
    private final String types;
    
    TYPEUSER(String types) {
        super(USER_TOKEN_CHECKER, SERVICE_USER);
        this.types = types;
    }

    @Override
    public CompletableFuture<AccessToken> check(AbsServerRequest req, String token) {
        return super.check(req, token).thenComposeAsync(at -> {
            if(at == null) {
                LOG.error("Invalid token,fail to parse `{}`", token);
                return CompletableFuture.completedFuture(null);
            }
            String type = AgentCache.get(at.caller);
            if(type != null && this.types.contains(type)) {
                return CompletableFuture.completedFuture(at);
            }

            //确定用户是否为指定类型的
            ServiceReqBuilder builder = new ServiceReqBuilder(req.serviceInfo(), SERVICE_USER)
                    .url("/getBaseInfo")
                    .traceId(req.traceId)
                    .cid(req.cid())
                    .token(token);
            return ServiceClient.serviceGet(builder).thenApplyAsync((hr)-> {
                if(hr.code == RetCode.OK && hr.data != null) {
                    String t = ValParser.getAsStr(hr.data, "type");
                    if(this.types.contains(t)) {
                        AgentCache.put(at.caller, t);
                    } else {
                        AgentCache.put(at.caller, ""); //避免反复请求
                        LOG.error("Not an {} user token, {}", this.types, token);
                        return null;
                    }
                }
                return at;
            }, IThreadPool.Pool);
        }, IThreadPool.Pool);
    }
}
