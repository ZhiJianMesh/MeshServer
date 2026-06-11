package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;

/**
 * `@{CONCAT|paraName,...}`
 * @author flyinmind of csdn.net
 *
 */
final class CONCAT extends ScriptElement {
    public CONCAT(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        this.paras = ApiParaHolder.parseHolders(paras);
        if(this.paras == null || this.paras.length < 2) {
            throw new InvalidParameterException("invalid concat config");
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        StringBuilder str = new StringBuilder(4096);
        for(ApiParaHolder s : this.paras) {
            str.append(s.get(req, resp)); //只做连接，不加连接符
        }
        return convertQuotes(str.toString());
    }
}