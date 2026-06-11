package cn.net.zhijian.mesh.frm.config.tokenchecker;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 通过调用/user/verify实现token校验。
 * 如果缓存中有token，则直接校验缓存中的，如果没有，则到user服务校验，
 * 成功后，会记入缓存，以备后用。
 * 校验token的同时，如果请求来自公网，则要判断用户是否有公网访问权限
 * 
 * @author flyinmind of csdn.net
 *
 */
class USER extends BASEUSER {
    private static final Logger LOG = LogUtil.getInstance();

    USER() {
        super(USER_TOKEN_CHECKER, SERVICE_USER);
    }

    @Override
    public CompletableFuture<AccessToken> check(AbsServerRequest req, String token) {
        return super.check(req, token).thenComposeAsync(at -> {
            if(at == null) {
                LOG.error("Invalid token,fail to parse `{}`", token);
                return CompletableFuture.completedFuture(null);
            }

            ServiceInfo si = req.serviceInfo();
            if(PartitionConfig.instance().isInCloud || !req.isExternal()) {
                return CompletableFuture.completedFuture(at);
            }

            int id = StringUtil.concatHashCode(ByteUtil.int2Hex(req.cid(), false), at.caller, at.callee);
            byte[] powers = CachedPowers.get(id);
            if(powers != null) {
                if(getRight(POWER_EXRERNAL_ACCESS, powers) <= 0) { //无外网访问权限
                    LOG.debug("No POWER_EXRERNAL_ACCESS for {}.{}=>{} in cache", req.cid(), at.caller, at.callee);
                    return CompletableFuture.completedFuture(null);
                }
                return CompletableFuture.completedFuture(at);
            }

            /*
             * 确定是否有外网访问权限，如果没有，并且请求来自外网，则认证失败。
             * 此处在启动后第一次访问会进入嵌套循环，最终被HttpServer的深度检查终止。
             * 因为/power/get也是需要用户鉴权的接口，所以它本身又会触发鉴权走到这里。
             * 因此将/power/get的token检查设置成无需验证外网权限的BASEUSE
             */
            ServiceReqBuilder builder = new ServiceReqBuilder(si, SERVICE_USER)
                    .url("/power/get?service=" + si.name)
                    .traceId(req.traceId)
                    .cid(req.cid())
                    .token(token);
            return ServiceClient.serviceGet(builder).thenApplyAsync((hr)-> {
                String p;
                byte[] pl;
                
                if(hr.code == RetCode.OK && hr.data != null) {
                    p = ValParser.getAsStr(hr.data, "power");
                    int len = p.length();
                    if(len == 0) {
                        pl = new byte[] {};
                    } else {
                        pl = new byte[len];
                        for(int i = 0; i < len; i += 2) {
                            pl[i] = (byte)ByteUtil.getBase64CharVal(p.charAt(i));
                            pl[i + 1] = (byte)ByteUtil.getBase64CharVal(p.charAt(i + 1));
                        }
                    }
                    CachedPowers.put(id, pl);
                } else {
                    pl = new byte[] {};
                    CachedPowers.put(id, pl);
                }

                if(getRight(POWER_EXRERNAL_ACCESS, pl) <= 0) { //无外网访问权限
                    LOG.debug("No POWER_EXRERNAL_ACCESS for {}.{}=>{} in db", req.cid(), at.caller, at.callee);
                    return null;
                }

                return at;
            }, IThreadPool.Pool);
        }, IThreadPool.Pool);
    }
    
    /**
     * 每两个字符定义一项权限，使用base64格式保存
     * @param type 权限类型，最多64种
     * @param powers 权限列表
     * @return 返回权限值
     */
    private int getRight(byte type, byte[] powers) {
        int len = powers.length;
        for(int i = 0; i < len; i += 2) {
            if(type == powers[i]) {
                return ((int)powers[i + 1]) & 0x2f; //最多64个，每个权限最多64种
            }
        }
        return -1;
    }
}
