package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.client.KeyStoreClient;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.StringUtil;

/**
 * `@{DECODE|keyName,[!]paraName}`
 * @author flyinmind of csdn.net
 *
 */
final class DECODE extends ScriptElement {
    private final String keyName;

    public DECODE(String paras/*keyName,parameter*/, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 2) {
            throw new InvalidParameterException("invalid DECODE config");
        }
        this.keyName = ApiParaHolder.takeStr(ss[0]).trim();
        this.paras = new ApiParaHolder[] {ApiParaHolder.parse(ss[1])};
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String v = paras[0].getAsString(req, resp);
        v = KeyStoreClient.decode(req, keyName, v);
        return convertQuotes(v);
    }
}
