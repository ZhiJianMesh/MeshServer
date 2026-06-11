package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.StringUtil;

/**
 * `@{SPLIT|para,lenOrSplitChar,spliter}`
 * 将para参数按固定长度len切割成多段（不足len的不会填充尾部）；或者通过分隔符分成多段；
 * 分隔后再使用分隔符spliter连接起来，连接时会加上合适的引号
 * @author flyinmind of csdn.net
 */
final class SPLIT extends ScriptElement {
    private final int len;
    private final char spliterBy;
    private final String spliter;

    public SPLIT(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 3) {
            throw new InvalidParameterException("there should be at least 3 parameters");
        }
        this.paras = new ApiParaHolder[] {ApiParaHolder.parse(ss[0].trim())};
        
        String s = ss[1].trim();
        if(s.matches("\\d+")) {
            this.len = Integer.parseInt(s);
            this.spliterBy = 0;
        } else {
            this.len = 0;
            this.spliterBy = ApiParaHolder.takeStr(s).charAt(0);
        }
        this.spliter = ApiParaHolder.takeStr(ss[2].trim());
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String s = this.paras[0].getAsString(req, resp);
        StringBuilder ss = new StringBuilder(2 * s.length());
        int sl = s.length();
        boolean needQuote = !IConst.EMPTY_STR.equals(this.quote);

        if(this.len > 0) {
            String a;
            for(int i = 0; i < sl; i += this.len) {
                if(i > 0) {
                    ss.append(this.spliter);
                }
    
                if(i + this.len < sl) {
                    a = s.substring(i, i + this.len);
                } else {
                    a = s.substring(i);
                }
                if(needQuote) {
                    ss.append(this.quote).append(a).append(this.quote);
                } else {
                    ss.append(a);
                }
            }
        } else {
            String[] arr = StringUtil.split(s, this.spliterBy, (char)0, false);
            int n = 0;
            for(String a : arr) {
                if(n > 0) {
                    ss.append(this.spliter);
                }
                if(needQuote) {
                    ss.append(this.quote).append(a).append(this.quote);
                } else {
                    ss.append(a);
                }
                n++;
            }
        }

        return ss.toString();
    }
}
