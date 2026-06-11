package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.ValParser;
/**
 * \@{PBKDFCHECK|[!]paraName, [!]savedStr}
 * 检查pbkdf产生的结果是否正确
 */
final class PBKDFCHECK extends ScriptElement {
    public PBKDFCHECK(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        this.paras = ApiParaHolder.parseHolders(paras);
        if(this.paras == null || this.paras.length < 2) {
            throw new IllegalArgumentException("Too few parameters in `" + paras + "`");
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String pwd = ValParser.parseString(this.paras[0].get(req, resp));
        String saved = ValParser.parseString(this.paras[1].get(req, resp));
        return SecureUtil.pbkdf2Check(pwd, saved);
    }
}
