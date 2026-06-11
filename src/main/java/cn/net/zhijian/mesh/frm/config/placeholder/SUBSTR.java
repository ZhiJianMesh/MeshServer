package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.StringUtil;

/**
 * `@{SUBSTR|[!]paraName,start[,len]}`
 * @author flyinmind of csdn.net
 *
 */
final class SUBSTR extends ScriptElement {
    private final int start;
    private final int end;

    public SUBSTR(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 2) {
            throw new InvalidParameterException("There should be 2 parameters");
        }
        this.paras = new ApiParaHolder[] {ApiParaHolder.parse(ss[0])};
        this.start = Integer.parseInt(ss[1]);
        if(ss.length > 2) {//len不设置，表示一直到末尾
            this.end = this.start + Integer.parseInt(ss[2]);
            if(this.start >= this.end) {
                throw new InvalidParameterException(this.start + ">=" + this.end);
            }
        } else {
            this.end = -1;
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String v = paras[0].getAsString(req, resp);
        if(this.start >= v.length()) {
            return IConst.EMPTY_STR;
        }
        if(end > 0) {
            if(this.end >= v.length()) {
                return convertQuotes(v.substring(start));
            }
            return convertQuotes(v.substring(start, end));
        }
        return convertQuotes(v.substring(start));
    }
}