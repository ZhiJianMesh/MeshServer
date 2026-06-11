package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.SecureUtil;

/**
 * `@{SHA256|paraName,...}`
 * @author flyinmind of csdn.net
 *
 */
final class SHA256 extends ScriptElement {
    public SHA256(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        this.paras = ApiParaHolder.parseHolders(paras);
        if(this.paras == null || this.paras.length < 1) {
            throw new InvalidParameterException("invalid SHA256 config");
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String s = concatStr(req, resp, paras);
        return SecureUtil.sha256(s);
    }
}