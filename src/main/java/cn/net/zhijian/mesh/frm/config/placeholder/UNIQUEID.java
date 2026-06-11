package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.Map;

import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 产生一个UUID，然后计算该字符串的绝对hash值
 * `@{UNIQUEID[|i\int]}`
 * @author flyinmind of csdn.net
 */
final class UNIQUEID extends ScriptElement {
    private final boolean isInt;

    public UNIQUEID(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        this.isInt = paras.equalsIgnoreCase("i") || paras.equalsIgnoreCase("int");
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String s = StringUtil.uuid();
        if(isInt) {
            return ValParser.absInt(s.hashCode());
        }
        long h = StringUtil.longHashCode(s);
        return ValParser.absLong(h);
    }
}
