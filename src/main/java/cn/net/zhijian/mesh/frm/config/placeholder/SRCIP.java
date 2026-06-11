package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.Map;

import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;

/**
 * 请求源地址IP
 * `@{SRCIP[|remote/request]}`
 * @author flyinmind of csdn.net
 *
 */
final class SRCIP extends ScriptElement {
    private final boolean remote;

    public SRCIP(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        this.remote = "remote".equalsIgnoreCase(paras);
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        return remote ? req.remoteAddr() : req.requestAddr();
    }
}
