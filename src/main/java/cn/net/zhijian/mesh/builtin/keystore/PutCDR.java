package cn.net.zhijian.mesh.builtin.keystore;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.Ecc.StrKP;
import cn.net.zhijian.util.StringUtil;

/**
 * 云上ROOT模式运行的公司的数据根密钥使用系统根密钥加密后存储，而不是使用公司登录密码加密后存储。
 * 在接口/company/putcdr、/company/getcdr中使用。
 * 其他模式都是用/keypair/putIfAbsent、/keypair/put、/keypair/get
 */
public class PutCDR extends KeyStoreBase {
    public PutCDR(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        String kp = req.getString("kp");
        StrKP sk;
        EccKeyPair ekp;
        try {
            sk = StrKP.parse(kp);
            ekp = EccKeyPair.parse(sk, false);
        } catch (InvalidKeySpecException | NoSuchAlgorithmException e) { //impossible
            return futureResult(RetCode.DATA_WRONG, "invalid data root key pair");
        }
        return encodeDataRootkey(serviceInfo(), kp).thenComposeAsync(encKp -> {
            if(StringUtil.isEmpty(encKp)) {
                return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to encode data root key");
            }
            req.put("kp", encKp); //覆盖传入的请求参数，坏方法
            addKeyPair(req.cid(), ekp);
            return super.handle(req, respData);
        }, Pool);
    }
}
