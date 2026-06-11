package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.bean.Relation;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.Calculator;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * `@{FOR|paraName,`separator`,...}`
 * @author flyinmind of csdn.net
 * 对paraName指定的数组进行循环拼接
 * 注意，用到字符串的地方，引号不能使用单引号，统一使用"`"，
 * 否则在字符串中出现了"{},()"等字符时处理会出现错误
 */
final class FOR extends ScriptElement {
    private static final String ITEM = "e";
    private static final String ITEM_HEADER = ITEM + ".";
    private static final String ITEM_NO = "i";
    private final String separator;
    private final ILoopElement[] eles;
    private final IFilter[] filters;
    private final ApiParaHolder list;
    
    public FOR(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        List<ApiParaHolder> pl = new ArrayList<>();
        int firstComma = paras.indexOf(',');
        String s = paras.substring(0, firstComma);

        //因为修改insert sql时会判断")"，添加update_timer，所以用中括号
        int start = s.indexOf('['); 
        if(start > 0) { //判断是否有过滤条件
            int level = 1;
            int end;
            int len = paras.length();
            char c;
            for(end = start + 1; end < len; end++) {
                c = paras.charAt(end);
                if(c == '[') {
                    level++;
                } else if(c == ']') {
                    level--;
                    if(level == 0) {
                        break;
                    }
                }
            }
            if(level != 0) {
                throw new InvalidParameterException("brackets not match in filter");
            }
            s = paras.substring(start + 1, end) //不包括最外层的括号
                    .replace("||", "+").replace("&&", "*")
                    .replace('[', '(').replace(']', ')'); //Calculator只识别小括号
            List<IFilter> ff = parseFilters(s, pl);
            s = paras.substring(0, start) + paras.substring(end + 1); //变成不带过滤条件的的格式
            this.filters = ff.toArray(new IFilter[0]);
        } else {
            s = paras;
            this.filters = null;
        }
        
        String[] ss = StringUtil.split(s, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 3) {
            throw new InvalidParameterException("there should be at least 4 parameters");
        }
        this.list = ApiParaHolder.parse(ss[0]);
        if(!this.list.isConst()) {
            pl.add(this.list);
        }
        
        this.separator = ApiParaHolder.takeStr(ss[1]);
        List<ILoopElement> eles = new ArrayList<>();

        for(int i = 2; i < ss.length; i++) {
            ILoopElement le = ILoopElement.parse(ss[i], quote, safeQuote);
            if(le != null) {
                eles.add(le);
                continue;
            }
            
            //支持字符串中有占位符
            List<ScriptElement> scrs = parseHolders(ss, i, i + 1, quote, safeQuote);
            pl.addAll(listReqParameters(scrs));//用于参数校验，不存入，在启动时无法发现字符串中未定义的参数
            ScriptElement[] sl = scrs.toArray(new ScriptElement[0]);
            if(sl.length == 1 && sl[0].paras.length == 1) { 
                if(sl[0].paras[0].isConst()) { //对于只有一项且是常量的情况，做个优化
                    eles.add(new STR(sl[0].paras[0].name));
                } else {
                    eles.add(new HOLDER(sl[0].paras[0]));
                }
            } else {
                eles.add(new COMPLEX(sl));
            }
        }
        
        this.paras = pl.toArray(new ApiParaHolder[] {}); //用于检查请求参数
        this.eles = eles.toArray(new ILoopElement[] {});
    }
    
    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        List<Object> ll = ValParser.parseList(this.list.get(req, resp));
        if(ll == null || ll.isEmpty()) {
            return IConst.EMPTY_STR;
        }

        StringBuilder str = new StringBuilder(4096);
        String s;
        int no = 0;
        int i = 0;
        for(Object o : ll) {
            if(filters != null) {
                s = "";
                for(IFilter f : filters) {
                    s += f.run(no, o, req, resp);
                }
                double result = Calculator.calculate(s);
                if(result == 0) { //条件不符合，不处理这条记录
                    no++;
                    continue;
                }
            }
            
            if(i > 0) {
                str.append(this.separator);
            }
            for(ILoopElement le : this.eles) {
                str.append(le.get(no, o, req, resp));
            }
            no++;
            i++;
        }
        return str.toString();
    }
    
    private List<IFilter> parseFilters(String filters, List<ApiParaHolder> pl) {
        String[] ss = StringUtil.split(filters, "+*", ApiParaHolder.QUOTATION_MARK, true, true);
        List<IFilter> ff = new ArrayList<>();
        
        for(String s : ss) {
            if(s.equals("*") || s.equals("+")) {
                ff.add(new FilterString(s));
            } else {
                IFilter.parse(s, ff, quote, safeQuote, pl);
            }
        }
        return ff;
    }
    
    interface ILoopElement {
        Object get(int sn, Object item, AbsServerRequest req, Map<String, Object> resp);
        
        /**
         * 
         * @param s 待解析字符串
         * @param quote 需替换的引号
         * @param safeQuote 将quote替换成为此引号
         * @return 循环单元
         */
        static ILoopElement parse(String s, String quote, String safeQuote) {
            if(s.startsWith(ITEM_HEADER)) {
                return new MEMBER(s.substring(ITEM_HEADER.length()), quote, safeQuote);
            }
            if(s.equals(ITEM_NO)) {
                return new NO();
            }
            if(s.equals(ITEM)) {
                return new ITEM(quote, safeQuote);
            }
            return null;
        }
    }
    
    static class STR implements ILoopElement {
        private final String s;
        STR(String s) {
            this.s = s;
        }

        @Override
        public Object get(int sn, Object item, AbsServerRequest req, Map<String, Object> resp) {
            return s;
        }        
    }
    
    static class NO implements ILoopElement {
        @Override
        public Object get(int sn, Object item, AbsServerRequest req, Map<String, Object> resp) {
            return sn;
        }        
    }
    
    static class COMPLEX implements ILoopElement {
        final ScriptElement[] paras;
        
        COMPLEX(ScriptElement[] paras) {
            this.paras = paras;
        }
        
        @Override
        public Object get(int sn, Object item, AbsServerRequest req, Map<String, Object> resp) {
            return runAll(paras, req, resp);
        }        
    }

    static class ITEM implements ILoopElement {
        final String quote;
        final String safeQuote;
        
        ITEM(String quote, String safeQuote) {
            this.quote = quote;
            this.safeQuote = safeQuote;
        }
        
        @Override
        public Object get(int sn, Object item, AbsServerRequest req, Map<String, Object> resp) {
            return convertQuotes(item, quote, safeQuote);
        }        
    }
    
    static class HOLDER implements ILoopElement {
        private final ApiParaHolder holder;
        
        HOLDER(ApiParaHolder holder) {
            this.holder = holder;
        }
        
        @Override
        public Object get(int sn, Object item, AbsServerRequest req, Map<String, Object> resp) {
            return holder.get(req, resp);
        }
    }
    
    static class MEMBER implements ILoopElement {
        final String seg;
        final String quote;
        final String safeQuote;
        
        MEMBER(String seg, String quote, String safeQuote) {
            this.seg = seg;
            this.quote = quote;
            this.safeQuote = safeQuote;
        }

        @Override
        public Object get(int sn, Object item, AbsServerRequest req, Map<String, Object> resp) {
            Map<String, Object> m = ValParser.parseObject(item);
            if(m != null) {
                return convertQuotes(m.get(seg), quote, safeQuote);
            }
            return IConst.EMPTY_STR;
        }        
    }
    
    interface IFilter {
        String run(int sn, Object item, AbsServerRequest req, Map<String, Object> resp);
        
        static void parse(String s, List<IFilter> filters, String quote, String safeQuote, List<ApiParaHolder> pl) {
            String[] ff = StringUtil.split(s, ',', ApiParaHolder.QUOTATION_MARK, true);
            char c;
            int i;
            String f = ff[0];
            int len = f.length();
            
            for(i = 0; i < len; i++) {
                c = f.charAt(i);
                if(c != '(' && c != '+' && c != '*') {
                    break;
                }
            }
            if(i > 0) {
                filters.add(new FilterString(f.substring(0, i)));
                f = f.substring(i);
            }
            ILoopElement le1 = ILoopElement.parse(f, quote, safeQuote);
            if(le1 == null) {
                ApiParaHolder aph = ApiParaHolder.parse(f);
                if(aph.isConst()) {
                    le1 = new STR(aph.name);
                } else {
                    pl.add(aph);
                    le1 = new HOLDER(aph);
                }                
            }
            
            int relation = Relation.parseRelation(ApiParaHolder.takeStr(ff[1]));
            f = ff[2];
            len = f.length();
            for(i = len - 1; i >= 0; i--) {
                c = f.charAt(i);
                if(c != ')' && c != '+' && c != '*') {
                    break;
                }
            }
            
            IFilter strFilter = null;
            if(i < len - 1) {
                strFilter = new FilterString(f.substring(i + 1));
                f = f.substring(0, i + 1);
            }
            ILoopElement le2 = ILoopElement.parse(f, quote, safeQuote);
            if(le2 == null) {
                ApiParaHolder aph = ApiParaHolder.parse(f);
                if(aph.isConst()) {
                    le2 = new STR(aph.name);
                } else {
                    pl.add(aph);
                    le2 = new HOLDER(aph);
                }
            }
            filters.add(new FilterCondition(relation, le1, le2));
            if(strFilter != null) {
                filters.add(strFilter);
            }
        }
    }
    
    static class FilterCondition implements IFilter {
        private final int valType;
        private final int relation;
        private final ILoopElement p1;
        private final ILoopElement p2;

        FilterCondition(int relation, ILoopElement p1, ILoopElement p2) {
            this.relation = Relation.relation(relation);
            this.valType = Relation.valType(relation);
            this.p1 = p1;
            this.p2 = p2;
        }
        
        @Override
        public String run(int sn, Object item, AbsServerRequest req, Map<String, Object> resp) {
            Object o1 = p1.get(sn, item, req, resp);
            Object o2 = p2.get(sn, item, req, resp);
            return Relation.compare(o1, o2, valType, relation) ? "1" : "0";
        }
    }
    
    static class FilterString implements IFilter {
        private final String s;
        
        FilterString(String s) {
            this.s  = s;
        }

        @Override
        public String run(int sn, Object item, AbsServerRequest req, Map<String, Object> resp) {
            return s;
        }
    }
}
