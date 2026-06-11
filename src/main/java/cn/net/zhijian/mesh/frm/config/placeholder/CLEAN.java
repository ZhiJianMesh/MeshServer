package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;

/**
 * `@{CLEAN|[!]paraName}`
 * 只适用于json类型，删除map中的字段名称及list中的逗号、括号
 * 此函数通常用在产生逆向索引功能中，删除json中没有必要的冗余信息
 * @author flyinmind of csdn.net
 *
 */
final class CLEAN extends ScriptElement {
    public CLEAN(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        this.paras = new ApiParaHolder[] { ApiParaHolder.parse(paras) };
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        Object v = paras[0].get(req, resp);
        StringBuilder sb = new StringBuilder(4096);
        objToStr(v, sb);
        return convertQuotes(sb.toString());
    }

    @SuppressWarnings("unchecked")
    private static void objToStr(Object o, StringBuilder sb) {
        if(o == null) {
            return;
        }
        if(o instanceof Map) {
            for(Object e : ((Map<String, Object>)o).values()) {
                objToStr(e, sb);
            }
        } else if(o instanceof List) {
            for(Object l : ((List<?>)o)) {
                objToStr(l, sb);
            }
        } else if(o instanceof String) {
            sb.append((String)o).append(' ');
        } else if(o instanceof Number) {
            sb.append(o).append(' ');
//            } else {
//                //ignore them all
        }
    }
}