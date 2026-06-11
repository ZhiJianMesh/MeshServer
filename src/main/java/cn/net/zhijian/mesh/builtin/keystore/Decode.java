package cn.net.zhijian.mesh.builtin.keystore;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;

/**
 * 使用数据根密钥解密
 * 数据根密钥用环境根密钥加密后存在keystore中，同时使用公司密码加密后备份在根环境
 */
public class Decode extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    
    public Decode(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }
    
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        int cid = req.getInt(EMBEDED_TOKEN_CID, NULL_COMPANY_ID);
        if(CompanyInfo.instance().id == cid) {
            //如果是本地公司的token，则可以指定任意公司cid，否则只能使用token中的
            int id = req.getInt("cid", NULL_COMPANY_ID);
            if(id > LOCAL_COMPANY_ID) {
                cid = id;
            }
        }

        return KeyStoreBase.getDataRootKey(cid, req.serviceInfo()).thenComposeAsync(kp -> {
            if(kp == null) {
                return futureResult(RetCode.NOT_EXISTS, "no root key");
            }
            String encodedPwd = req.getString("pwd");
            byte[] bPwd = ByteUtil.base642bin(encodedPwd);
            byte[] plain;
            try {
                plain = kp.decrypt(bPwd);
            } catch (Exception e) {
                LOG.error("Fail to decode pwd from company({})", req.cid(), e);
                return futureResult(RetCode.DATA_WRONG, "invalid pwd,fail to decode");
            }
            Map<String, Object> data = MapBuilder.of("pwd", new String(plain, DEFAULT_CHARSET));
            return futureResult(data);
        }, Pool);
    }
}
