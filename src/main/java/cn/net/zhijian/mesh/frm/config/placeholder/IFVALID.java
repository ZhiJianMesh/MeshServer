package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.StringUtil;

/**
 * `@{IFVALID|paraName,...}`
 * @author flyinmind of csdn.net
 * 如果第一个参数不为空，则将后面的字符串都连接起来返回，否则返回空字符串
 */
final class IFVALID extends ScriptElement {
    private final ScriptElement[] holders;
    
    public IFVALID(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 2) {
            throw new InvalidParameterException("there should be at least 2 parameters");
        }
        List<ScriptElement> scrs = parseHolders(ss, 1, ss.length, quote, safeQuote);
        if(scrs == null) {
            throw new InvalidParameterException("invalid parameters");
        }
        List<ApiParaHolder> aphs = listReqParameters(scrs);
        aphs.add(0, ApiParaHolder.parse(ss[0]));
        this.paras = aphs.toArray(new ApiParaHolder[] {});
        this.holders = scrs.toArray(new ScriptElement[0]);
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        Object o = this.paras[0].get(req, resp);
        if(o == null) {
            return IConst.EMPTY_STR;
        }
        if(o instanceof Boolean) {
            if(!(Boolean)o) {
                return IConst.EMPTY_STR;
            }
        } else if(IConst.EMPTY_STR.equals(o)) {
            return IConst.EMPTY_STR;
        }
        return runAll(holders, req, resp);
    }
}
