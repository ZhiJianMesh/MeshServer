package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.Map;

import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.StringUtil;

/**
 * `@{UUID[|16/64]}`
 * @author flyinmind of csdn.net
 *
 */
final class UUID extends ScriptElement {
    private final boolean base64;

    public UUID(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        this.base64 = !paras.equals("16");
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        return base64 ? StringUtil.base64UUID() : StringUtil.uuid();
    }
}
