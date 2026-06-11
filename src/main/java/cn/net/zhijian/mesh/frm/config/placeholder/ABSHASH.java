package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.Map;

import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.ValParser;

/**
 * `@{ABSHASH|paraName,...}`,`@{ABSHASH|i,paraName,...}`
 * @author flyinmind of csdn.net
 */
final class ABSHASH extends HASH {
    public ABSHASH(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        if(isInt) {
            String s = concatStr(req, resp, paras);
            return ValParser.absInt(s.hashCode());
        }
        long h = concatLHashCode(req, resp, paras);
        return ValParser.absLong(h);
    }
}
