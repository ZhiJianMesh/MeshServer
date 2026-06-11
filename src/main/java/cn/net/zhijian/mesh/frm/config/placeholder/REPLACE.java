package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;

/**
 * `@{REPLACE|[!]paraName,search,replaceWith}`
 * @author flyinmind of csdn.net
 *
 */
final class REPLACE extends ScriptElement {
    public REPLACE(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        this.paras = ApiParaHolder.parseHolders(paras);
        if(this.paras == null || this.paras.length < 3) {
            throw new InvalidParameterException("There should be 3 parameters");
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String v = paras[0].getAsString(req, resp);
        String regular = paras[1].getAsString(req, resp);
        String with = paras[2].getAsString(req, resp);
        String s = v.replaceAll(regular, with);
        return convertQuotes(s);
    }
}