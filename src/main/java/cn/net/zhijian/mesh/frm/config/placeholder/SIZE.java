package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;

/**
 * `@{SIZE|[!]paraName}`
 * 返回数组参数的长度
 * @author flyinmind of csdn.net
 *
 */
class SIZE  extends ScriptElement {
    public SIZE(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        this.paras = new ApiParaHolder[] {ApiParaHolder.parse(paras)};
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        Object v = paras[0].get(req, resp);
        if(v instanceof List) {
            return ((List<?>)v).size();
        }else if(v instanceof Map) {
            return ((Map<?,?>)v).size();
        } else if(v instanceof String) {
            return ((String)v).length();
        } else if(v != null && v.getClass().isArray()) {
            return ((Object[])v).length;
        }
        return 0;
    }
}
