package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.bean.Relation;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * `@{ADD|type,n1,n2}`
 * @author flyinmind of csdn.net
 *
 */
class ADD extends ScriptElement {
    protected final int valType; //0:int,1:long,2:float,3:double
    private final double places; //保留位数

    public ADD(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 3) {
            throw new InvalidParameterException("invalid config");
        }

        String tp = ApiParaHolder.takeStr(ss[0]);
        int p = tp.indexOf('.');
        if(p > 0) { //带了小数点后的保留位数
            tp = tp.substring(0, p);
            p = ValParser.parseInt(ss[0].substring(p + 1), -1);
            if(p > 7 || p < 0) { //保留0位时，转字符串后，后面仍然要跟".0"
                throw new InvalidParameterException("invalid decimal places");
            }
            this.places = Math.pow(10, p);
        } else {
            this.places = -1.0; //小于0时，不做处理
        }
        
        this.valType = Relation.parseType(tp);
        if(!Relation.isNumber(this.valType)) {
            throw new InvalidParameterException("invalid type");
        }
        this.paras = new ApiParaHolder[] {
            ApiParaHolder.parse(ss[1]),
            ApiParaHolder.parse(ss[2])
        };
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        if(valType == Relation.TYPE_INT) {
            int v1 = ValParser.parseInt(paras[0].get(req, resp), 0);
            int v2 = ValParser.parseInt(paras[1].get(req, resp), 0);
            return v1 + v2;
        }

        if(valType == Relation.TYPE_LONG) {
            long v1 = ValParser.parseLong(paras[0].get(req, resp), 0);
            long v2 = ValParser.parseLong(paras[1].get(req, resp), 0);
            return v1 + v2;
        }

        if(valType == Relation.TYPE_FLOAT) {
            float v1 = ValParser.parseFloat(paras[0].get(req, resp), 0);
            float v2 = ValParser.parseFloat(paras[1].get(req, resp), 0);
            return round(v1 + v2);
        }
        double v1 = ValParser.parseDouble(paras[0].get(req, resp), 0);
        double v2 = ValParser.parseDouble(paras[1].get(req, resp), 0);
        return round(v1 + v2);
    }
    
    Object round(float v) {
        return places >= 0 ? (Math.round(v * places) / places) : v;
    }
    
    Object round(double v) {
        return places >= 0 ? (Math.round(v * places) / places) : v;
    }
 }
