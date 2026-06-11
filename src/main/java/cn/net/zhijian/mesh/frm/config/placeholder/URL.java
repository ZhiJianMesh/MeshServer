package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * `@{URL|cmd,paraName}`
 * @author flyinmind of csdn.net
 *
 */
final class URL extends ScriptElement {
    private static final int ENCODE = 0;
    private static final int DECODE = 1;
    private static final int APPEND = 2;
    private final int cmd;
    
    public URL(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 2) {
            throw new InvalidParameterException("there should be at least 2 parameters");
        }
        String cmd = ApiParaHolder.takeStr(ss[0]);
        if(cmd.equalsIgnoreCase("encode")) {
            this.cmd = ENCODE;
        } else if(cmd.equalsIgnoreCase("decode")) {
            this.cmd = DECODE; //para
        } else {
            this.cmd = APPEND; //url, name1, value1, name2, value2...
            if(ss.length < 4 && ss.length % 2 != 0) {
                throw new InvalidParameterException("3 parameters at least, and paras must be pairs");
            }
        }
        this.paras = new ApiParaHolder[ss.length - 1];
        for(int i = 1; i < ss.length; i++) {
            this.paras[i - 1] = ApiParaHolder.parse(ss[i]);
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        if(this.cmd == APPEND) {
            Object s = this.paras[0].get(req, resp);
            UrlPathInfo url = new UrlPathInfo(s != null ? s.toString() : IConst.EMPTY_STR);
            for(int i = 1; i < this.paras.length; i += 2) {
                String k = ValParser.parseString(this.paras[i].get(req, resp));
                url.appendPara(k, this.paras[i + 1].get(req, resp), true);
            }
            return url.toString();
        }
        
        String s = "";
        for(ApiParaHolder aph : this.paras) {
            s += ValParser.parseString(aph.get(req, resp));
        }
        if(this.cmd == ENCODE) {
            return UrlPathInfo.urlEncode(s);
        }
        //else if(this.cmd == DECODE)
        return UrlPathInfo.urlDecode(s);
    }
}