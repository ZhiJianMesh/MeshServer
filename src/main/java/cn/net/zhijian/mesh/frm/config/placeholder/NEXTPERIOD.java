package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.DateUtil.PeriodType;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * `@{NEXTPERIOD|type(D|M|W|H|C),val}`
 * val D/W/M/H为与起点的时间间隔，C为周期时长，毫秒为单位
 * @author flyinmind of csdn.net
 * 下一个周期时间
 */
final class NEXTPERIOD extends ScriptElement {
    public NEXTPERIOD(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 2) {
            throw new InvalidParameterException("there should be at least 2 parameters");
        }
        this.paras = new ApiParaHolder[] {
            ApiParaHolder.parse(ss[0]),
            ApiParaHolder.parse(ss[1])
        };
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        char cType = ValParser.parseString(this.paras[0].get(req, resp), "H").charAt(0);
        long v = ValParser.parseLong(this.paras[1].get(req, resp), 0L);
        
        PeriodType pt;
        if(cType == 'W') {
            pt = PeriodType.WEEK;
            v -= 60000L * PartitionConfig.instance().timeZone;
        } else if(cType == 'M') {
            pt = PeriodType.MONTH;
            v -= 60000L * PartitionConfig.instance().timeZone;
        } else if(cType == 'D') {
            pt = PeriodType.DAY;
        } else if(cType == 'H') {
            pt = PeriodType.HOUR;
        } else {
            pt = PeriodType.CYCLE;
        }
        while(v < 0) {
            v += 86400 * 1000; //跳到下一天
        }
        return DateUtil.recentNextTime(pt, System.currentTimeMillis(), v);
    }
}
