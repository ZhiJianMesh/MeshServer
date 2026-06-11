package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * `@{CLEAR|[!]paraName,`char_list`}`
 * 将paraName中在char_list中的字符都清除掉
 * 只适合处理字符串参数
 * @author flyinmind of csdn.net
 *
 */
final class CLEAR extends ScriptElement {
    final String charList;
    
    public CLEAR(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 2) {
            throw new InvalidParameterException("invalid CLEAR config");
        }
        this.paras = new ApiParaHolder[] { ApiParaHolder.parse(ss[0]) };
        this.charList = StringUtil.unescape(ApiParaHolder.takeStr(ss[1]));
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String s = ValParser.parseString(paras[0].get(req, resp));
        char[] cl = s.toCharArray();
        int len = cl.length;
        StringBuilder sb = new StringBuilder(len);
        for(char c : cl) {
            if(this.charList.indexOf(c) < 0) {
                sb.append(c);
            }
        }
        return convertQuotes(sb.toString());
    }
}