package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.ValParser;

/**
 * `@{ELEMENTOF|[!]paraName,sn|name|[!]paraName]}` 
 * 取map对象中的一个字段，或取数组中的一个元素
 * 如果是map，字段名可以支持用'.'分割成多级
 * @author flyinmind of csdn.net
 *
 */
final class ELEMENT extends ScriptElement {
    public ELEMENT(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = paras.split(",");
        if(ss.length < 2) {
            throw new InvalidParameterException("invalid ELEMENTOF config");
        }
        this.paras = new ApiParaHolder[] {
            ApiParaHolder.parse(ss[0].trim()),
            ApiParaHolder.parse(ss[1].trim())
        };
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        Object v = paras[0].get(req, resp);
        if(v == null) {
            return null;
        }
        if(v instanceof List) {
            List<Object> l = (List<Object>)v;
            int sn = ValParser.parseInt(paras[1].get(req, resp), -1);
            if(sn < 0 || l.size() <= sn) {
                return null;
            }
            return l.get(sn);
        }
        if(!(v instanceof Map)) {
            return null;
        }
        String seg = ValParser.parseString(paras[1].get(req, resp));
        return  ValParser.getObject((Map<String, Object>)v, seg); // kv
    }
}
