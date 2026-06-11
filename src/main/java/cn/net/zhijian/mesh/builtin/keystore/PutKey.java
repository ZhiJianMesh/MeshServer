package cn.net.zhijian.mesh.builtin.keystore;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.LogUtil;

public class PutKey extends KeyStoreBase {
    private static final Logger LOG = LogUtil.getInstance();
    
    public PutKey(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }
    
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        return getDataRootKey(req.cid(), req.serviceInfo()).thenComposeAsync(kp -> {
            if(kp == null) {
                return futureResult(RetCode.NOT_EXISTS, "no private key");
            }
            String pwd = req.getString("pwd");
            byte[] bPwd = ByteUtil.base642bin(pwd);
            byte[] cipheredPwd;
            try {
                cipheredPwd = Ecc.instance(kp.ver).encrypt(bPwd, kp.pub);
            } catch (Exception e) {
                LOG.error("Fail to encode pwd {}.{}.{}",
                        req.cid(), req.serviceInfo().name, req.get("name"));
                return futureResult(RetCode.INTERNAL_ERROR, "fail to decode");
            }
            req.put("pwd", ByteUtil.bin2base64(cipheredPwd));
            return super.handle(req, respData);
        }, Pool);
    }
}
