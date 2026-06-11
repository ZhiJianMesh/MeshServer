package cn.net.zhijian.mesh.builtin.bios;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.dbworker.TreeDBWorker;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsTokenChecker;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.Totp;

/**
 * 使用om totp密码或公司密码都可以
 * 如果是OM totp密码，验证会快速一些，公司密码需要到根环境验证，速度慢一些。
 * 密码在放入token时，都需要经过base64(sha256(pwd))运算
 * @author flyinmind of csdn.net
 */
final class OmPwdChecker extends AbsTokenChecker {
    static final String OM_TOTP_ITEM = "/service/" + SERVICE_OM + "/keys/totp";
    private static final Logger LOG = LogUtil.getInstance();
    private static final Totp TOTP = new Totp(6, 60);
    private static final VerifyResult verifyResult = new VerifyResult("", -1L);

    public OmPwdChecker() {
        super("OMPWD");
    }

    @Override
    public CompletableFuture<AccessToken> check(AbsServerRequest req, String token) {
        ServiceInfo si = req.serviceInfo();
        CompanyInfo ci = CompanyInfo.instance();
        String caller = req.getString("service");
        if(StringUtil.isEmpty(caller)) { //token中无caller，所以必须传入
            caller = SERVICE_BACKEND;
        }
        AccessToken at = new AccessToken(null,
                PartitionConfig.instance().partition,
                caller, SERVICE_BIOS,
                System.currentTimeMillis() + 10000,
                AccessToken.EXT_FEATURE_ALL + ci.id,
                SIGNTYPE_PWD, TOKENTYPE_SERVICE);
        return CompletableFuture.supplyAsync(()-> {
            //查询本机的metadb，如果不是主bios，数据不是最及时的版本
            //因为密码不常变化，也不经常使用，所以关系不大
            TreeDBWorker db = (TreeDBWorker)si.getLocalDBWorker(IDBConst.TREEDB, BiosMetaHandler.DB);
            if(db == null) {
                LOG.error("Fail to get treedb {}.{}", SERVICE_BIOS, BiosMetaHandler.DB);
                return null;
            }

            String secret = db.getValue(OM_TOTP_ITEM); //totp密码
            if(StringUtil.isEmpty(secret)) {
                LOG.warn("{} not exist", OM_TOTP_ITEM);
                return null;
            }

            return TOTP.verify(secret, token) ? at : null;
        }, Pool).thenComposeAsync(omAt -> {
            if(omAt != null) {
                return CompletableFuture.completedFuture(omAt);
            }

            LOG.debug("Fail to verify om TOTP password,then verify company password");
            long cur = System.currentTimeMillis();
            //一个环境只有一个公司验证，密码长期不变，即使多个公司，也只使用首要公司
            if(!verifyResult.token.equals(token)) {
                verifyResult.token = token;
                verifyResult.expiresAt = -1L;
            } else if(cur < verifyResult.expiresAt) {//避免频繁的重复验证
                return CompletableFuture.completedFuture(at);
            }

            return ci.verifyPwd(SecureUtil.sha256(token)).thenApplyAsync(hr -> {
                if(hr.code != RetCode.OK) {
                    LOG.error("Fail to verify company password,result:{}", hr.brief());
                    verifyResult.expiresAt = -1L;
                    return null;
                }
                verifyResult.expiresAt = cur + 600 * 1000;
                return at;
            });
        }, Pool);
    }
    
    private static class VerifyResult {
        public volatile String token;
        public volatile long expiresAt;
        
        public VerifyResult(String token, long expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }
}