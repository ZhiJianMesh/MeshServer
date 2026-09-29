package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.StringUtil;

/**
 * `@{STRPAD|para,'fillChar',len}或@{STRPAD|para,len,'fillChar'}`
 * 字符串不足len时，在前面或后面填充fillChar，超过len时则从前面或后面截断
 * @author flyinmind of csdn.net
 */
final class STRPAD extends ScriptElement {
    private final char fillChar;
    private final int len;
    private final boolean fillHead;

    public STRPAD(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 3) {
            throw new InvalidParameterException("there should be at least 3 parameters");
        }
        this.paras = new ApiParaHolder[] {ApiParaHolder.parse(ss[0])};
        if(ss[1].matches("\\d+")) {
            this.len = Integer.parseInt(ss[1]);
            this.fillChar = ApiParaHolder.takeStr(ss[2]).charAt(0);
            this.fillHead = false;
        } else {
            this.len = Integer.parseInt(ss[2]);
            this.fillChar = ApiParaHolder.takeStr(ss[1]).charAt(0);
            this.fillHead = true; //第一个参数是字符串则在前面填充或掐断
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String s = this.paras[0].getAsString(req, resp);
        int l = s.length();
        if(l > this.len) {
            s = fillHead ? s.substring(l - this.len) : s.substring(0, this.len);
        } else if(fillHead) {
            for(;l < this.len; l++) {
                s = this.fillChar + s;
            }
        } else {
            for(;l < this.len; l++) {
                s += this.fillChar;
            }
        }
        return convertQuotes(s);
    }
}
