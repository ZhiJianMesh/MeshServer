package cn.net.zhijian.mesh.frm.config.tokenchecker;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig.DeployMode;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.mesh.frm.tokenworker.PwdTokenWorker;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.LruCache;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 用在远程执行服务器维护命令时
 * token有两种签名方式，通过公司私钥签名，或者通过临时token密码签名，
 * 如果使用临时密码，则五分钟不使用就会自动过期，
 * 如果使用公司私钥签名，则一天后会自动过期。
 */
public class BACKEND extends COMPANY {
    private static final Logger LOG = LogUtil.getInstance();
    private static final int MAX_VALID_TIME = 300 * 1000; //ms

    private static final LruCache<Integer, CompanyAccess> Accesses = new LruCache<>(MAX_VALID_TIME);

    BACKEND() {
        super(BACKEND_TOKEN_CHECKER);
    }

    public CompletableFuture<AccessToken> check(AbsServerRequest req, String token) {
        int signType = AccessToken.getSignType(token);
        if(signType == IOAuth.SIGNTYPE_COMPANYKEY) { //公司authKey私钥签名
            return super.check(req, token);
        }

        if(signType != IOAuth.SIGNTYPE_PWD) {
            LOG.error("Invalid token signType {}. it must be SIGNTYPE_PWD", signType);
            return CompletableFuture.completedFuture(null);
        }

        //远程接入码签名，远程接入码在闲置5分钟后，会失效
        int cid = req.cid();
        CompanyAccess c = Accesses.get(req.cid());
        if(c == null) {
            CompanyInfo ci = CompanyInfo.instance();
            if(ci.id != cid) {
                LOG.error("Invalid company id {}, not local company({})", cid, ci.id);
                return CompletableFuture.completedFuture(null);
            }
            c = new CompanyAccess(ci);
            Accesses.put(cid, c);
        }
        return c.check(token);
    }

    /**
     * 设置token
     * @param ci 公司信息
     * @param v token密码字符串
     */
    public CompletableFuture<HandleResult> setTokenPwd(CompanyInfo ci, String v) {
        if(StringUtil.isEmpty(v)) {
            LOG.error("setToken,invalid token");
            return CompletableFuture.completedFuture(new HandleResult(RetCode.WRONG_PARAMETER, "invalid token"));
        }
        CompanyAccess c = Accesses.computeIfAbsent(ci.id, k -> new CompanyAccess(ci));
        return c.setTokenPwd(v);
    }
    
    public ITokenWorker getTokenWorker(int cid) {
        CompanyAccess c = Accesses.get(cid);
        return c == null || c.expiresAt < System.currentTimeMillis() ? null : c.tokenWorker;
    }

    private static class CompanyAccess {
        final CompanyInfo ci;
        ITokenWorker tokenWorker = null;
        long expiresAt = 0L;

        CompanyAccess(CompanyInfo ci) {
            this.ci = ci;
        }

        /**
         * 设置临时访问token
         * @param v token密码字符串
         */
        CompletableFuture<HandleResult> setTokenPwd(String v) {
            if(PartitionConfig.instance().mode == DeployMode.SINGLETON) {
                //单例，无集群多实例同步的烦恼，直接记录在本地，不必请求bios服务
                tokenWorker = new PwdTokenWorker(v);
                expiresAt = System.currentTimeMillis() + MAX_VALID_TIME;
                return CompletableFuture.completedFuture(HandleResult.OK);
            }

            ServiceReqBuilder builder = ServiceClient.backendReqBuilder(SERVICE_BIOS)
                    .url("/keys/setTokenPwd")
                    .put("pwd", v) //加密后存入bios服务
                    .traceId("setTokenPwd" + ci.id)
                    .token(ci.adminToken(SERVICE_BIOS).generate())
                    .cid(ci.id); //必须保证有正确的cid头部
            return BiosClient.put(builder).thenApplyAsync(r -> {
                if(r.code != RetCode.OK) {
                    LOG.warn("Fail to call setTokenPwd({}), result:{}", ci.id,  r.brief());
                    return r;
                }
                long cur = System.currentTimeMillis();
                tokenWorker = new PwdTokenWorker(v);
                expiresAt = cur + MAX_VALID_TIME;
                LOG.debug("setTokenPwd('{}'),cur:{},expiresAt:{}", v, cur, expiresAt);
                return r;
            }, Pool);
        }
        
        CompletableFuture<AccessToken> check(String token) {
            long cur = System.currentTimeMillis();
            if(PartitionConfig.instance().mode == DeployMode.SINGLETON) {
                //单例，无集群多实例同步的烦恼，设置时直接生成tokenWorker，
                //所以使用时可以直接使用本地的tokenWorker
                return CompletableFuture.completedFuture(parseToken(token, cur));
            }

            if(cur >= expiresAt) {
                ServiceReqBuilder builder = ServiceClient.backendReqBuilder(SERVICE_BIOS)
                        .url("/keys/getTokenPwd")
                        .traceId("getTokenPwd" + ci.id)
                        .token(ci.adminToken(SERVICE_BIOS).generate())
                        .cid(ci.id); //必须保证有正确的cid头部
                return BiosClient.get(builder).thenApplyAsync(hr -> {
                    if(hr.code != RetCode.OK) {
                        LOG.warn("Fail to getTokenPwd, result:{}", hr.brief());
                        //查不到时也生成一个随机的，避免没有开启，仍然恶意访问，导致频繁访问bios服务
                        //同时，如果出现恶意访问，会被锁定约5分钟
                        expiresAt = cur + MAX_VALID_TIME;
                        tokenWorker = new PwdTokenWorker(StringUtil.genRandomCode(10));
                        return null;
                    }
                    String s = ValParser.getAsStr(hr.data, "key"); //pwd,setAt
                    int pos = s.indexOf(',');
                    String tokenPwd;
                    if(pos < 0) {
                        tokenPwd = s;
                        expiresAt = cur + MAX_VALID_TIME;
                    } else {
                        tokenPwd = s.substring(0, pos);
                        long t = Long.parseLong(s.substring(pos + 1));
                        expiresAt = t + MAX_VALID_TIME;
                    }
                    tokenWorker = new PwdTokenWorker(tokenPwd);
                    LOG.debug("getTokenPwd('{}'),cur:{},expiresAt:{}", ci.id, cur, expiresAt);
                    return parseToken(token, cur);
                }, Pool);
            }
            return CompletableFuture.completedFuture(parseToken(token, cur));
        }
        
        private AccessToken parseToken(String token, long cur) {
            if(expiresAt <= cur) {
                return null;
            }
            AccessToken at = AccessToken.parse(token, tokenWorker);
            if(at != null && !at.expired() && at.caller.equals(IOAuth.ADMIN_UID)) {
                int pos = at.ext.indexOf(AccessToken.EXT_SEPARATOR);//account,cid
                if(pos <= 0) {
                    LOG.error("Invalid token ext:{},from {} to {}", at.ext, at.caller, at.callee);
                    return null;
                }
                int cid = Integer.parseInt(at.ext.substring(pos + 1));
                if(cid == this.ci.id) {
                    expiresAt = cur + MAX_VALID_TIME; //验证成功，则延长有效期
                    return at;
                }
                LOG.error("Invalid token token.cid.{} != access.cid.{},from {} to {}",
                        cid, this.ci.id, at.caller, at.callee);
            }
            return null;
        }
    }
}
