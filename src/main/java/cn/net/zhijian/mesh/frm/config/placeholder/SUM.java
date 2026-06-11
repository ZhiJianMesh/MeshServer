package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.bean.Relation;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * `@{SUM|type,[!]paraName,[,segName]]}`
 * 将所有成员求和，如果指定了字段名，则源数据必须为一个对象数组，
 * 如果是数组的数组，segName可以指定为列号，
 * 都不指定，则认为传入的是数值list
 * type支持long、double、int、float等，也可以用简写
 * @author flyinmind of csdn.net
 *
 */
final class SUM extends AbsListElement {
    private final int valType;
    private final double places; //保留位数

    public SUM(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 2) {
            throw new InvalidParameterException("invalid SUM config");
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
        parseListPara(ss[1]);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        Object v = paras[0].get(req, resp);
        if(!(v instanceof List)) {
            return 0;
        }
        if(valType == Relation.TYPE_LONG) {
            return runLong((List<Object>)v);
        }
        if(valType == Relation.TYPE_INT) {
            return (int)runLong((List<Object>)v);
        }
        double sum = runDouble((List<Object>)v);
        if(valType == Relation.TYPE_DOUBLE) {
            return places >= 0 ? (Math.round(sum * places) / places) : sum;
        }
        //如果先处理保留位数，再强转为float，保留位数失败
        //这种方式处理效率高，但是保留小数末尾的0会丢失
        float fv = (float)sum;
        return places >= 0 ? (Math.round(fv * places) / places) : fv;
    }
    
    @SuppressWarnings("unchecked")
    private double runDouble(List<Object> l) {
        double v = 0;
        if(this.seg == null) { //普通list
            for(Object o : l) {
                v += ValParser.parseDouble(o, 0);
            }
        } else if(this.col >= 0) {//list的元素是list,[[...],[...],...]
            for(Object o : l) {
                if(o instanceof Object[]) {
                    Object[] row = (Object[])o;
                    v += ValParser.parseDouble(row[this.col], 0);
                } else if(o instanceof List) {
                    List<Object> row = (List<Object>)o;
                    v += ValParser.parseDouble(row.get(this.col), 0);
                }
            }
        } else {
            for(Object o : l) {
                if(!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>)o;
                v += ValParser.parseDouble(m.get(this.seg), 0);
            }
        }
        return v;
    }
    
    @SuppressWarnings("unchecked")
    private long runLong(List<Object> l) {
        long v = 0;
        if(this.seg == null) { //普通list
            for(Object o : l) {
                v += ValParser.parseLong(o, 0);
            }
        } else if(this.col >= 0) {//list的元素是list,[[...],[...],...]
            for(Object o : l) {
                if(o instanceof Object[]) {
                    Object[] row = (Object[])o;
                    v += ValParser.parseLong(row[this.col], 0);
                } else if(o instanceof List) {
                    List<Object> row = (List<Object>)o;
                    v += ValParser.parseLong(row.get(this.col), 0);
                }
            }
        } else { //对象中的某个属性
            for(Object o : l) {
                if(!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>)o;
                v += ValParser.parseLong(m.get(this.seg), 0);
            }
        }
        return v;
    }
}
