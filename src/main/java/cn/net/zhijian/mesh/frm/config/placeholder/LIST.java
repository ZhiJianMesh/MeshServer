package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * `@{LIST|[!]paraName[.segName|col][,quote]}`
 * 解决NORMAL中数组自动加[]的问题，导致在sql中无法使用。
 * 将所有成员输出为一个列表，如果指定了字段名，则源数据必须为一个对象数组，
 * 且需包含该字段
 * @author flyinmind of csdn.net
 *
 */
final class LIST extends AbsListElement {
    private final String eQuote; //元素的引号，默认没有

    public LIST(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 1) {
            throw new InvalidParameterException("invalid LIST config");
        }
        parseListPara(ss[0]);
        if(ss.length > 1) {
            if(ss[1].length() == 1) {
                this.eQuote = ss[1];
            } else {
                this.eQuote = ApiParaHolder.takeStr(ss[1]);
            }
        } else {
            this.eQuote = quote;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        Object v = paras[0].get(req, resp);
        if(v instanceof List) {
            return run((List<Object>)v);
        }
        if(!(v instanceof Map)) {
            return IConst.EMPTY_STR;
        }
        return run((Map<String, Object>)v); //kv
    }
    
    @SuppressWarnings("unchecked")
    private String run(List<Object> l) {
        StringBuilder sb = new StringBuilder(4096);
        int i = 0;

        if(this.seg == null) { //普通list
            for(Object o : l) {
                if(i > 0) {
                    sb.append(',');
                }
                append(o, sb);
                i++;
            }
        } else if(this.col >= 0) {//list的元素是list,[[...],[...],...]
            for(Object o : l) {
                if(i > 0) {
                    sb.append(',');
                }
                if(o instanceof Object[]) {
                    Object[] row = (Object[])o;
                    append(row[this.col], sb);
                } else if(o instanceof List) {
                    List<Object> row = (List<Object>)o;
                    append(row.get(this.col), sb);
                } else {
                    continue;
                }
                i++;
            }
        } else {
            for(Object o : l) {
                if(!(o instanceof Map)) {
                    continue;
                }
                if(i > 0) {
                    sb.append(',');
                }
                Map<String, Object> m = (Map<String, Object>)o;
                append(m.get(this.seg), sb);
                i++;
            }
        }

        return sb.toString();
    }

    private String run(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder(4096);
        int i = 0;
        if(StringUtil.isEmpty(this.seg) || this.seg.equals("k")) { //只列举key
            for(String e : m.keySet()) {
                if(i > 0) {
                    sb.append(',');
                }
                append(e, sb);
                i++;
            }
            return sb.toString();
        }

        for(Object e : m.values()) { //只列举value
            if(i > 0) {
                sb.append(',');
            }
            append(e, sb);
            i++;
        }
        return sb.toString();
    }
    
    private void append(Object o, StringBuilder sb) {
        if(o == null) {
            return;
        }

        if(o instanceof String){
            if(!IConst.EMPTY_STR.equals(quote) && safeQuote != null) {
                if(!IConst.EMPTY_STR.equals(this.eQuote)) {
                    sb.append(this.eQuote);
                    sb.append(((String)o).replace(quote, safeQuote));
                    sb.append(this.eQuote);
                } else {
                    sb.append(((String)o).replace(quote, safeQuote));
                }
            } else {
                sb.append(o);
            }
        } else if(o instanceof Number) {
            if(!IConst.EMPTY_STR.equals(this.eQuote)) {
                sb.append(this.eQuote);
                sb.append(o);
                sb.append(this.eQuote);
            } else {
                sb.append(o);
            }
        } else {
            String s = JsonUtil.objToJson(o);
            if(!IConst.EMPTY_STR.equals(quote) && safeQuote != null) {
                if(!IConst.EMPTY_STR.equals(this.eQuote)) {
                    sb.append(this.eQuote);
                    sb.append(s.replace(quote, safeQuote));
                    sb.append(this.eQuote);
                } else {
                    sb.append(s.replace(quote, safeQuote));
                }
            } else {
                sb.append(s);
            }
        }
    }
}
