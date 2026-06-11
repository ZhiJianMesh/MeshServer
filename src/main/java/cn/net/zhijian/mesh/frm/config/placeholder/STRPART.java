package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * `@{STRPART|para,spliter,partNo}`
 * 将字符串按spliter分隔成多个子字符串，取出编号为partNo的子字符串，编号从0开始。
 * spliter可以是正则表达式
 * @author flyinmind of csdn.net
 */
final class STRPART extends ScriptElement {
    private final String spliter;
    private final int no;

    public STRPART(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 3) {
            throw new InvalidParameterException("there should be at least 3 parameters");
        }
        this.paras = new ApiParaHolder[] {ApiParaHolder.parse(ss[0])};
        this.spliter = ApiParaHolder.takeStr(ss[1]);
        this.no = ValParser.parseInt(ss[2], 0); //小于0的情况，返回最后一个
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String s = this.paras[0].getAsString(req, resp);
        String[] ss = s.split(this.spliter);
        if(ss.length == 0 || ss.length <= this.no) {
            return IConst.EMPTY_STR;
        }
        return this.no >= 0 ? ss[this.no] : ss[ss.length - 1];
    }
}
