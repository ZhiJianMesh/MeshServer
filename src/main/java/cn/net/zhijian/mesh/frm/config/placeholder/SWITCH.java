package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.bean.Relation;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.StringUtil;

/**
 * `@{SWITCH|a,condition,b,p1,p2...,|....}`
 * @author flyinmind of csdn.net
 * 如果条件满足，则将后面所有字段拼接起来返回
 */
final class SWITCH extends ScriptElement {
    private final Switch[] switches;
    
    public SWITCH(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] conditions = StringUtil.split(paras, '|', ApiParaHolder.QUOTATION_MARK, true);
        if(conditions.length < 1) {
            throw new InvalidParameterException("invalid if config,should include at least one 'if' and one 'else'");
        }
        
        List<Switch> switches = new ArrayList<>();
        Relation r;
        List<ApiParaHolder> pl = new ArrayList<>(); //记录需要校验的参数列表
        int n;
        String s;
        String[] ss;
        char[] BLANKS = " ,\n\r\t".toCharArray();

        for(int i = 0; i < conditions.length; i++) {
            s = StringUtil.trim(conditions[i], BLANKS) ;
            ss = StringUtil.split(s, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
            if(i == conditions.length - 1) { //最后一个，必须为else
                r = new Relation();
                n = 0;
            } else {
                if(ss.length < 4) {
                    throw new InvalidParameterException("invalid condition config,too few parameters");
                }
                ApiParaHolder aph1 = ApiParaHolder.parse(ss[0]);
                ApiParaHolder aph2 = ApiParaHolder.parse(ss[2]);
                if(aph1.isRequestPara()) {
                    pl.add(aph1); //添加到参数列表中，才能在启动时检查参数是否存在
                }
                if(aph2.isRequestPara()) {
                    pl.add(aph2);
                }
                r = new Relation(ss[1], aph1, aph2);
                n = 3;
            }
            
            List<ScriptElement> aphs = parseHolders(ss, n, ss.length, quote, safeQuote);
            switches.add(new Switch(r, aphs.toArray(new ScriptElement[0])));
            pl.addAll(listReqParameters(aphs));
        }
        this.switches = switches.toArray(new Switch[] {});
        this.paras = pl.toArray(new ApiParaHolder[] {}); //用于参数校验
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String o = null;
        for(Switch s : switches) {
            o = s.run(req, resp);
            if(o != null) {
                break;
            }
        }
        return o == null ? IConst.EMPTY_STR : o;
    }
    
    static class Switch {
        private final Relation relation;
        private final ScriptElement[] paras;

        Switch(Relation relation, ScriptElement[] paras) {
            this.relation = relation;
            this.paras = paras;
        }
        
        String run(AbsServerRequest req, Map<String, Object> resp) {
            if(!this.relation.compare(req, resp)) {
                return null;
            }
            return runAll(paras, req, resp);
        }
    }
}
