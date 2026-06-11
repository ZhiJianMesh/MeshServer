package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;

/**
 * `@{UPPER|[!]paraName}`
 * @author flyinmind of csdn.net
 *
 */
final class UPPER extends ScriptElement {
    public UPPER(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        this.paras = new ApiParaHolder[] {ApiParaHolder.parse(paras)};
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String v = paras[0].getAsString(req, resp);
        return convertQuotes(v.toUpperCase());
    }
}