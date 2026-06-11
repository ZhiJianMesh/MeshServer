package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;

/**
 * `@{COALESCE|[!]para1,[!]para2...}`
 * 返回第一个非空的参数，如果都为空，则返回一个空字符串
 * 如果只有两个参数，则可以实现IFNULL的功能
 * @author flyinmind of csdn.net
 */
final class COALESCE extends ScriptElement {
    public COALESCE(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        this.paras = ApiParaHolder.parseHolders(paras);
        if(this.paras == null || this.paras.length < 1) {
            throw new InvalidParameterException("no parameter in list");
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        Object o;

        for(ApiParaHolder holder : this.paras) {
            o = holder.get(req, resp);
            if(o != null) {
                return convertQuotes(o);
            }
        }

        return IConst.EMPTY_STR;
    }
}
