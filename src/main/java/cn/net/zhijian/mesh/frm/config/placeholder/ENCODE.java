package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.client.KeyStoreClient;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.StringUtil;

/**
 * `@{ENCODE|keyName[#keyTime],[!]paraName}`
 * 对参数内容进行加密，keyName指定密码箱中的密码，后面可以用#分隔加keyTime，指定有效时长
 * @author flyinmind of csdn.net
 *
 */
final class ENCODE extends ScriptElement {
    private final String keyName;
    private final long keyTime; //密钥最大有效期，单位天

    public ENCODE(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        //keyName, parameter[, keyTime]
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 2) {
            throw new InvalidParameterException("invalid ENCODE config");
        }
        this.keyName = ApiParaHolder.takeStr(ss[0]).trim();
        this.paras = new ApiParaHolder[] {ApiParaHolder.parse(ss[1])};
        long keyTime = KeyStoreClient.DEFAULT_INTERVAL;
        if(ss.length > 2) {
            keyTime = 86400000L * Integer.parseInt(ss[2]);
            if(keyTime < KeyStoreClient.MIN_INTERVAL) {
                keyTime = KeyStoreClient.MIN_INTERVAL;
            }
        }
        this.keyTime = keyTime;
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String v = paras[0].getAsString(req, resp);
        return KeyStoreClient.encode(req, keyName, keyTime, v);
    }
}

