package cn.net.zhijian.mesh.js;


import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.quickjs.JSObject;
import cn.net.zhijian.quickjs.JavascriptMethod;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.DateUtil.PeriodType;

/**
 * 给js提供基本的success与error返回
 * @author flyinmind of csdn.net
 *
 */
public final class JsMesh {
    @JavascriptMethod
    public String success(JSObject data) {
        return new HandleResult(RetCode.OK, RetCode.INFO_SUCCESS, data.toMap()).toString();
    }

    @JavascriptMethod
    public String error(int code, String info) {
        return new HandleResult(code, info, null).toString();
    }

    /**
     * @param periodType D/W/M/C
     * @param old 原来的时间点，只有periodType为C时有用
     * @param val D/W/M与起点的时间间隔，C为周期时长，毫秒为单位
     * @return 下一个周期值
     */
    @JavascriptMethod
    public long recentNextPeriod(String periodType, long old, long val) {
        PeriodType type;
        String s = periodType.toUpperCase();
        long v = val;
        if(s.equals("D")) {
            type = PeriodType.DAY;
            v -= PartitionConfig.instance().timeZone * 60000;
        } else if(s.equals("W")) {
            type = PeriodType.WEEK;
            v -= PartitionConfig.instance().timeZone * 60000;
        } else if(s.equals("M")) {
            type = PeriodType.MONTH;
            v -= PartitionConfig.instance().timeZone * 60000;
        } else if(s.equals("H")) {
            type = PeriodType.HOUR;
        } else {
            type = PeriodType.CYCLE;
        }
        if(v < 0) {
            v += 86400L * 1000; //跳到下一天
        }
        return DateUtil.recentNextTime(type, old, v);
    }
    
    @JavascriptMethod
    public int timeZone() {
        return PartitionConfig.instance().timeZone;
    }
}
