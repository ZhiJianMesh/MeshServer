package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.bean.TV;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.Calculator;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * `@{CALCULATE|type.precision,n1,`+(`,n2,`-`, n3, ')-'...}`
 * @author flyinmind of csdn.net
 *
 */
class CALCULATE extends ScriptElement {
    private final int valType; //返回类型,0:int,1:long,2:float,3:double
    private final double places; //保留位数
    private final ScriptElement[] holders;

    public CALCULATE(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        boolean dirFormula = false;
        if(ss.length < 3) {
            if(ss.length < 2) {
                throw new InvalidParameterException("invalid calculate config");
            }
            dirFormula = ss[1].charAt(0) != ApiParaHolder.QUOTATION_MARK
                    || ss[1].charAt(ss[1].length() - 1) != ApiParaHolder.QUOTATION_MARK;
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
        
        this.valType = TV.parseType(tp);
        if(!TV.isNumber(this.valType)) {
            throw new InvalidParameterException("invalid type config");
        }
        
        int start = 1;
        if(dirFormula) { //不用占位符，直接写算式，先将算符分开，再找变量
            ss = splitFormula(ss[1]);
            start = 0;
        }
        
        List<ScriptElement> aphs = parseHolders(ss, start, ss.length, quote, safeQuote);
        if(aphs == null) {
            throw new InvalidParameterException("invalid formula config");
        }
        this.paras = listReqParameters(aphs).toArray(new ApiParaHolder[] {});
        this.holders = aphs.toArray(new ScriptElement[0]);
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String str = runAll(this.holders, req, resp);//连接起来，形成算式
        double v = Calculator.calculate(str);
        if(valType == TV.TYPE_INT) {
            return (int)v;
        }
        if(valType == TV.TYPE_LONG) {
            return (long)v;
        }
        if(valType == TV.TYPE_FLOAT) {
            float fv = (float)v;//如果先处理保留位数，再强转为float，保留位数失败
            return places >= 0 ? (Math.round(fv * places) / places) : fv;
        }
        return places >= 0 ? (Math.round(v * places) / places) : v;
    }
    
    
    private String[] splitFormula(String str) {
        char[] cc = str.toCharArray();
        List<String> ss = new ArrayList<>();
        String s = "";
        boolean isOpr = false;
        for(char c : cc) {
            switch(c) {
            case '+':
            case '-':
            case '*':
            case '/':
            case '(':
            case ')':
                if(!isOpr) {
                    if(!s.isEmpty()) {
                        ss.add(s.trim());
                        s = "";
                    }
                    isOpr = true;
                }
                s += c;
                break;
            default:
                if(isOpr) {
                    if(!s.isEmpty()) {
                        ss.add("'" + s + '\'');
                        s = "";
                    }
                    isOpr = false;
                }
                s += c;
                break;
            }
        }
        if(!s.isEmpty()) {
            if(isOpr) {
                ss.add("'" + s + '\'');
            } else {
                ss.add(s.trim());
            }
        }
        return ss.toArray(new String[] {});
    }
}
