package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.bean.Relation;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.StringUtil;

/**
 * `@{CONDITION|a,type.relation,b,o1,o2}`
 * 判断a、b是否满足relation，满足返回o1，否则返回o2
 * o1、o2如果未输入，默认使用"1"、"0"
 * 返回内容始终是不加引号的字符串
 * @author flyinmind of csdn.net
 *
 */
class CONDITION extends ScriptElement {
    private final Relation relation;
    private final ScriptElement[] trueVal;
    private final ScriptElement[] falseVal;

    public CONDITION(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 3) {
            throw new InvalidParameterException("invalid condition config,too few parameters");
        }

        List<ApiParaHolder> pList = new ArrayList<>();
        ApiParaHolder p1 = ApiParaHolder.parse(ss[0]);
        ApiParaHolder p2 = ApiParaHolder.parse(ss[2]);
        if(p1.isRequestPara()) {
            pList.add(p1);
        }
        if(p2.isRequestPara()) {
            pList.add(p2);
        }
        if(ss.length > 3) {
            List<ScriptElement> list = parseHolders(ss, 3, 4, quote, safeQuote);
            if(list == null) {
                throw new InvalidParameterException("invalid condition true config");
            }
            pList.addAll(listReqParameters(list));
            this.trueVal = list.toArray(new ScriptElement[0]);
        } else {
            this.trueVal = new ScriptElement[] {ScriptElement.create("1", EleType.NUM, quote, safeQuote)};
        }
        if(ss.length > 4) {
            List<ScriptElement> list = parseHolders(ss, 4, 5, quote, safeQuote);
            pList.addAll(listReqParameters(list));
            this.falseVal = list.toArray(new ScriptElement[0]);
        } else {
            this.falseVal = new ScriptElement[] {ScriptElement.create("0", EleType.NUM, quote, safeQuote)};
        }

        this.paras = pList.toArray(new ApiParaHolder[] {});
        this.relation = new Relation(ss[1], p1, p2);
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        if(relation.compare(req, resp)) {
            return runAll(trueVal, req, resp);
        }
        return runAll(falseVal, req, resp);
    }
}
