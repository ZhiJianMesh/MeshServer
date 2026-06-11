package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.StringUtil;

/**
 * `@{IFNULL|[!]para1,'null_val'[,num]}`
 * 用于将null值替换成sql可以辨识的字符串。
 * 如果参数存在，则默认加一对quote返回；不存在，则输出null_val指定的值，比如null。
 * 如果有num|obj属性，则结果前后不会加上引号。
 * @author flyinmind of csdn.net
 */
final class IFNULL extends ScriptElement {
    private final boolean quoteIt;

    public IFNULL(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 1) {
            throw new InvalidParameterException("invalid IFNULL config");
        }
        ApiParaHolder nullVal;
        if(ss.length > 1) {
            nullVal = ApiParaHolder.parse(ss[1]);
        } else {
            nullVal = ApiParaHolder.parse("null");
        }
        this.paras = new ApiParaHolder[] {ApiParaHolder.parse(ss[0]), nullVal};
        if(ss.length > 2) {
            String s = ss[2].toLowerCase();
            this.quoteIt = !(s.equals("obj") || s.equals("object")
                             || s.equals("num") || s.equals("number"));
        } else {
            this.quoteIt = true;
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        Object o = this.paras[0].get(req, resp);
        if(o == null) {
            o = this.paras[1].get(req, resp);
            if(o == null) {
                return "null";
            }
        }

        if(this.quoteIt) {
            return this.quote + convertQuotes(o) + this.quote;
        }
        return convertQuotes(o);
    }
}
