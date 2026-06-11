package cn.net.zhijian.mesh.builtin.keystore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.ValParser;

/**
 * 获得一个解密后的密钥
 * 密钥在keystore中存储前，都使用datarootkey加密了，
 * 获取时需要使用datarootkey解密后才可以使用
 */
public class GetKey extends KeyStoreBase {
    private static final Logger LOG = LogUtil.getInstance();
    
    public GetKey(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }
    
    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        return super.handle(req, respData).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                return CompletableFuture.completedFuture(hr);
            }
            
            List<Object> list = ValParser.getAsList(hr.data, "keys");
            if(list == null || list.isEmpty()) {
                return futureResult(RetCode.NOT_EXISTS, "no keys");
            }
            return getDataRootKey(req.cid(), req.serviceInfo()).thenComposeAsync(kp -> {
                if(kp == null) {
                    return futureResult(RetCode.NOT_EXISTS, "no root key");
                }
                List<Map<String, Object>> keys = new ArrayList<>();
                for(Object one : list) {
                    Map<String, Object> key = ValParser.parseObject(one);
                    String pwd = ValParser.getAsStr(key, "pwd");
                    byte[] bPwd = ByteUtil.base642bin(pwd);
                    try {
                        byte[] plainPwd = kp.decrypt(bPwd);
                        key.put("pwd", ByteUtil.bin2base64(plainPwd));
                        keys.add(key);
                    } catch (Exception e) {
                        LOG.error("Fail to decode pwd {}.{}.{}.{},`{}`", req.cid(),
                                req.getString(IConst.EMBEDED_TOKEN_CALLER),
                                req.get("name"), kp.ver, pwd, e);
                    }
                }
                Map<String, Object> data = MapBuilder.of("keys", keys);
                return futureResult(data);
            }, Pool);
        }, Pool);
    }
}
