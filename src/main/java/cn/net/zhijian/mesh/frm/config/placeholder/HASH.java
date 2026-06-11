package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Arrays;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.bean.Relation;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.StringUtil;

/**
 * 第一个参数如果是i或l，则表示输出整型或长整型，不是i、l，则是普通参数，类型默认使用long
 * `@{HASH|i or l,paraName,...}`
 * @author flyinmind of csdn.net
 *
 */
class HASH extends ScriptElement {
    protected final boolean isInt;
    
    public HASH(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 1) {
            throw new InvalidParameterException("invalid HASH config");
        }
        int tp = Relation.parseType(ApiParaHolder.takeStr(ss[0]));
        int n = (tp != Relation.TYPE_INT && tp != Relation.TYPE_LONG ? 0 : 1); //第一个参数可能不是类型
        this.isInt = (tp == Relation.TYPE_INT);
        if(ss.length <= n) {
            throw new InvalidParameterException("invalid parameters `" + paras + "`");
        }
        this.paras = ApiParaHolder.parseHolders(Arrays.copyOfRange(ss, n, ss.length));
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        if(isInt) {
            return concatStr(req, resp, paras).hashCode();
        }
        return concatLHashCode(req, resp, paras);
    }
}
