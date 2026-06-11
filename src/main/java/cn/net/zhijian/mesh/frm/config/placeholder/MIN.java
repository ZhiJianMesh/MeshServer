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
 * `@{MIN|type,[!]paraName[,segName]}`
 * 在数组中找到最小的值
 * 如果是对象数组，可以指定对象中字段的名称，
 * 如果是数组的数组，可以指定数组中的列号
 * @author flyinmind of csdn.net
 *
 */
final class MIN extends AbsListElement {
    private final int valType; //返回类型,0:int,1:long,2:float,3:double

    public MIN(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 2) {
            throw new InvalidParameterException("invalid LIST config");
        }
        this.valType = Relation.parseType(ApiParaHolder.takeStr(ss[0]));
        if(!Relation.isNumber(this.valType)) {
            throw new InvalidParameterException("invalid type config");
        }
        parseListPara(ss[1]);
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        List<Object> ll = ValParser.parseList(this.paras[0].get(req, resp));
        if(ll == null || ll.isEmpty()) {
            return 0;
        }
        if(this.valType == Relation.TYPE_INT) {
            return (int)minLong(ll);
        }
        if(this.valType == Relation.TYPE_LONG) {
            return minLong(ll);
        }
        if(this.valType == Relation.TYPE_DOUBLE) {
            return minDouble(ll);
        }
        return (float)minDouble(ll);
    }
    
    private long minLong(List<Object> ll) {
        long min = Long.MAX_VALUE;
        long n;
        
        if(this.seg == null) { //普通list
            for(Object o : ll) {
                n = ValParser.parseLong(o, 0);
                if(min > n) {
                    min = n;
                }
            }
        } else if(this.col >= 0) { //数组列表
            for(Object o : ll) {
                List<Object> l = ValParser.parseList(o);
                n = ValParser.parseLong(l.get(this.col), 0);
                if(min > n) {
                    min = n;
                }
            }
        } else { //对象列表
            for(Object o : ll) {
                Map<String, Object> m = ValParser.parseObject(o);
                n = ValParser.parseLong(m.get(this.seg), 0);
                if(min > n) {
                    min = n;
                }
            }
        }  
        return min;
    }
    
    private double minDouble(List<Object> ll) {
        double min = Double.MAX_VALUE;
        double n;
        if(this.seg == null) { //普通list
            for(Object o : ll) {
                n = ValParser.parseDouble(o, 0);
                if(min > n) {
                    min = n;
                }
            }
        } else if(this.col >= 0) { //数组列表
            for(Object o : ll) {
                List<Object> l = ValParser.parseList(o);
                n = ValParser.parseDouble(l.get(this.col), 0);
                if(min > n) {
                    min = n;
                }
            }
        } else { //对象列表
            for(Object o : ll) {
                Map<String, Object> m = ValParser.parseObject(o);
                n = ValParser.parseDouble(m.get(this.seg), 0);
                if(min > n) {
                    min = n;
                }
            }
        }
        return min;
    }
}
