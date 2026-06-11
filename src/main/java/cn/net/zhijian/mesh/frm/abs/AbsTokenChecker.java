package cn.net.zhijian.mesh.frm.abs;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.FifoCache;

/**
 * tokenchecker是运行在被调用方实例上的
 * 以下示例是服务A调用服务B的情况，
 * 1）A向Auth.auth请求B服务的token；
 * 2）获得token后，携带token访问B；
 * 3）B拿token到Auth.verify验证，如果通过才可以访问；
 * 因为A、B都可以是多节点，所以缓存token时需要用token作为key
 * 不然，A服务只要有一个节点token在B上缓存成功，
 * 其他A的节点都可以仿照此token格式，都可以访问，而不需要分别向Auth申请token
 * 这样存在一些风险，让节点可以冒充合法的A服务节点
 * Bios<--Auth|<--A1,A2-->B1,B2
 *             |           |   |
 *      Verify|<----------/---/
 * @author flyinmind of csdn.net
 *
 */
public abstract class AbsTokenChecker implements IOAuth, IThreadPool {
    public static final char TOKEN_NAME_SPLITER = '-'; //用于分隔APP、APPS、NODE与具体服务名称
    
    /**
     * 本服务作为被调方时的token缓存。
     * 因为每次校验token需要访问verify，或者进行ecc、codebook运算，损耗性能，
     * 所以将它们缓存一段时间，再次使用时，直接从缓存中加载。
     * 必须定期清除，因为token会定期变化，如果不清理，会有很多缓存垃圾
     */
    protected static final FifoCache<Long, AccessToken> CachedTokens = new FifoCache<>(
            1800,//半小时
            10000);//单实例保存1万个活跃用户的token，大约需要4M内存
    
    //缓存用户拥有哪些平台类权限
    protected static final FifoCache<Integer, byte[]> CachedPowers = new FifoCache<>(
            1800, //半小时
            10000); //单实例保存1万个活跃用户的权限，所需内存很少

    public final String name;

    /**
     * 
     * @param req 请求
     * @param token 令牌
     * @return token对象
     */
    public abstract CompletableFuture<AccessToken> check(AbsServerRequest req, String token);

    public AbsTokenChecker(String name) {
        this.name = name.toUpperCase();
    }
    /**
     * 判断是否是user token，如果是，caller就是帐号id
     * @return true为user token
     */
    public boolean isUserToken() {
        return false;
    }
    
    public void addAvailables(ApiInfo ai) {
        ai.addExtPara(IConst.EMBEDED_TOKEN_CALLEE);
        ai.addExtPara(IConst.EMBEDED_TOKEN_CALLER);
        ai.addExtPara(IConst.EMBEDED_TOKEN_PARTITION);
        ai.addExtPara(IConst.EMBEDED_TOKEN_EXT);
    }
    
    public void addParas(Map<String, Object> params, AccessToken at) {
        params.put(IConst.EMBEDED_TOKEN_CALLER, at.caller);
        params.put(IConst.EMBEDED_TOKEN_CALLEE, at.callee);
        params.put(IConst.EMBEDED_TOKEN_PARTITION, at.partition);
        params.put(IConst.EMBEDED_TOKEN_EXT, at.ext);
    }

    public static void clearCache() {
        CachedTokens.clear();
        CachedPowers.clear();
    }
}