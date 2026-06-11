package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.Map;

import cn.net.zhijian.mesh.bean.Relation;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.ValParser;

/**
 * `@{DIV|type,n1,n2}`
 * @author flyinmind of csdn.net
 *
 */
final class DIV extends ADD {
    public DIV(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        if(valType == Relation.TYPE_INT) {
            int v1 = ValParser.parseInt(paras[0].get(req, resp), 0);
            int v2 = ValParser.parseInt(paras[1].get(req, resp), 0);
            return v2 != 0 ? v1 / v2 : 0;
        }
        if(valType == Relation.TYPE_LONG) {
            long v1 = ValParser.parseLong(paras[0].get(req, resp), 0);
            long v2 = ValParser.parseLong(paras[1].get(req, resp), 0);
            return v2 != 0 ? v1 / v2 : 0L;
        }
        if(valType == Relation.TYPE_FLOAT) {
            float v1 = ValParser.parseFloat(paras[0].get(req, resp), 0);
            float v2 = ValParser.parseFloat(paras[1].get(req, resp), 0);
            return v2 != 0 ? round(v1 / v2) : 0F;
        }
        double v1 = ValParser.parseDouble(paras[0].get(req, resp), 0);
        double v2 = ValParser.parseDouble(paras[1].get(req, resp), 0);
        return v2 != 0 ? round(v1 / v2) : 0D;
    }
}
