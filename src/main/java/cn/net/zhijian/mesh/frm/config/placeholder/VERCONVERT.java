package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * `@{VERCONVERT|segName[,tostr]}`
 * 将版本号（Major.Minor.Patch）转为9位整数
 * @author flyinmind of csdn.net
 */
final class VERCONVERT extends ScriptElement {
    private final boolean toStr;
    
    public VERCONVERT(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length > 1) {
            this.toStr = ss[1].equalsIgnoreCase("tostr");
        } else {
            this.toStr = false;
        }
        this.paras = new ApiParaHolder[] {ApiParaHolder.parse(ss[0])};
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        if(this.toStr) {
            int v = ValParser.parseInt(paras[0].get(req, resp), 0);
            return StringUtil.intToVer(v);
        } else {
            String s = ValParser.parseString(paras[0].get(req, resp));
            return StringUtil.verToInt(s);
        }
    }
}

