package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.ValParser;

/**
 * 计算参数的hash绝对值，求余max后返回
 * `@{HASHMOD|mod,paraName,...}`
 * @author flyinmind of csdn.net
 *
 */
final class HASHMOD extends ScriptElement {
    private final int mod;
    
    public HASHMOD(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        int pos = paras.indexOf(ApiParaHolder.PARA_SEPARATOR);
        if(pos < 0) {
            throw new InvalidParameterException("invalid HASHMOD config");
        }
        this.mod = Integer.parseInt(paras.substring(0, pos).trim());
        if(this.mod <= 0 || this.mod == Integer.MAX_VALUE) {
            throw new InvalidParameterException("invalid mod value `" + paras + "`");
        }
        this.paras = ApiParaHolder.parseHolders(paras.substring(pos + 1).trim());
        if(this.paras == null || this.paras.length < 1) {
            throw new InvalidParameterException("no valid parameters `" + paras + "`");
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        int v = concatStr(req, resp, paras).hashCode();
        v = ValParser.absInt(v);
        return v % mod;
    }
}
