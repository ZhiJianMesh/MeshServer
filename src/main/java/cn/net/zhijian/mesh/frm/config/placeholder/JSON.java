package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * `@{JSON|segName,default,quote,safequote}`
 * 将对象转成json字符串，并且将其中的引号替换为合适的符号，也可以指定不替换。
 * NORMAL本身就是将对象转为json字符串的，但是不能单独指定引号替换，所以单独出来。
 * 如果不指定quote与safeQuote，则与NORMAL相同，使用外部指定的quote与safeQuote
 * @author flyinmind of csdn.net
 */
final class JSON extends ScriptElement {
    private final ApiParaHolder defaultVal; //如果不存在，输出的字符串
    
    public JSON(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 1) {
            throw new InvalidParameterException("no segment name in JSON");
        }
        
        ApiParaHolder para = ApiParaHolder.parse(ss[0]);
        if(ss.length > 1) {
            this.defaultVal = ApiParaHolder.parse(ss[1]);
            this.paras = new ApiParaHolder[] {para, this.defaultVal};
        } else {
            this.defaultVal = null;
            this.paras = new ApiParaHolder[] {para};
        }
        
        if(ss.length > 2) {
            String s = ApiParaHolder.takeStr(ss[2]);
            this.quote = s.equals("0") || IConst.EMPTY_STR.equals(s) ? null : s;
        }

        if(ss.length > 3) {
            String s = ApiParaHolder.takeStr(ss[3]);
            this.safeQuote = s.equals("0") || IConst.EMPTY_STR.equals(s) ? null : s;
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        Object o = paras[0].get(req, resp);
        if(o == null) {
            return ValParser.parseString(this.defaultVal.get(req, resp));
        }
        String s = JsonUtil.objToJson(o);
        if(this.quote == null || this.safeQuote == null) {
            return s;
        }
        return s.replace(this.quote, this.safeQuote);
    }
}
