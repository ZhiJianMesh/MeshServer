package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;
import java.util.TimeZone;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * `@{NOW|format[,timzezonoffset]}`
 * 与`@{#reqAt}`类似，在一次请求处理中，值都是接到请求的那个时间点，不会因为多次引用而不同，
 * `@{#reqAt}`不能指定格式，`@{NOW}`可以指定输出格式
 * @author flyinmind of csdn.net
 */
final class NOW extends ScriptElement {
    private final String fmt; //日期格式化信息
    private final int fmtVal;

    public NOW(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        int fmtVal = UTC.NONE;
        String fmt = null;

        this.paras = null;
        if(ss.length > 0) {
            String s = ApiParaHolder.takeStr(ss[0]).trim();
            fmtVal = UTC.parseFmt(s);
            fmt = fmtVal == UTC.FORMAT ? s : null;
            
            if(ss.length > 1) { //offset，不格式化输出时，没有必要
                if(ss[1].matches("\\d+")) { //如果直接设置数字，判断一下范围
                    int offset = Integer.parseInt(ss[1]);
                    if(Math.abs(offset) > 12 * 3600 * 1000) { //正负12个时区
                        throw new InvalidParameterException("invalid offset config");
                    }
                }
                this.paras = new ApiParaHolder[] {ApiParaHolder.parse(ss[1])};
            }
        }
        
        //NONE时，表示有格式化字符串，为日期格式化字符串，比如yyyy-MM-dd
        this.fmt = fmt;
        this.fmtVal = fmtVal;
    }
    
    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        //NOW默认使用服务器本地时区设置，
        //除了unitxxx,base64,hex,ms外，其他格式化显示有时区问题
        TimeZone tz = TimeZone.getDefault();
        if(paras != null) {
            int offset = ValParser.parseInt(paras[0].get(req, resp), 0);
            tz.setRawOffset(offset);
        }
        
        return UTC.formatTime(this.fmtVal, this.fmt, req.reqTime, tz);
    }
}
