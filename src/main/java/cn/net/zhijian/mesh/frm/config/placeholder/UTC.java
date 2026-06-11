package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * `@{UTC|utc,offset,outputFmt,[,inputUnit]}`
 * 将UTC时间转为由offset指定的时区的时间
 * @author flyinmind of csdn.net
 */
final class UTC extends ScriptElement {
    private static final String FMT_UNIT = "unit";
    private static final String FMT_MONTHS = "months";
    private static final String FMT_DAYOFMONTH = "dayofmonth";
    private static final String FMT_DAYOFYEAR = "dayofyear";
    private static final String FMT_MONTH = "month";
    private static final String FMT_HEX = "hex";
    private static final String FMT_BASE64 = "base64";
    private static final String FMT_MONTHSTART = "monthstart";
    private static final String FMT_MONTHEND = "monthend";
    private static final String FMT_WEEKSTART = "weekstart";
    private static final String FMT_WEEKEND = "weekend";

    static final int NONE = 0; //原样输出
    static final int FORMAT = Integer.MIN_VALUE; //格式化字符串

    static final int HEX = -1;
    static final int BASE64 = -2;
    private static final int MONTHS = -3; //从公元元年1月到当前的月数
    private static final int DAYOFMONTH = -5; //几号，1号返回0
    private static final int MONTH = -6; //一年中的第几个月，一月返回0
    private static final int DAYOFYEAR = -7; //一年中的第几天，第一天返回0
    private static final int WEEKSTART = -8; //当前时间所在星期第一天的00:00:00
    private static final int WEEKEND = -9;//当前时间所在星期的下个星期第一天的00:00:00
    private static final int MONTHSTART = -10; //当前时间所在月份第一天的00:00:00
    private static final int MONTHEND = -11;//当前时间所在月份的下个月第一天的00:00:00
    
    private static final int UNIT_MONTH = -1; //输入公元0年1月到现在的月份数
    private static final int UNIT_YMD = -2; //与日期字符串一样，不受时区影响
    
    private final String outputFmt; //日期格式化信息
    private final int outputFmtVal;
    private final int inputUnit; //输入UTC值的单位，默认为1

    public UTC(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 2) {
            throw new InvalidParameterException("Invalid utc config");
        }
        this.paras = new ApiParaHolder[] {
            ApiParaHolder.parse(ss[0]), //utc时间戳，单位由inputUnit指定，默认为1ms
            ApiParaHolder.parse(ss[1]) //时区偏移，单位分钟，东区为正数
        };
        String fmt;
        int inputUnit = 1;
        int fmtVal = FORMAT;
        
        if(ss.length > 2) {
            String s = ApiParaHolder.takeStr(ss[2]).trim();
            fmtVal = parseFmt(s);
            if(ss.length > 3) {
                boolean valid = true;
                if(ss[3].matches("\\d+")) {
                    inputUnit = Integer.parseInt(ss[3]);
                    valid = inputUnit > 0; 
                } else if(ss[3].equalsIgnoreCase("month")) {
                    inputUnit = UNIT_MONTH;
                } else if(ss[3].equalsIgnoreCase("ymd")) {
                    inputUnit = UNIT_YMD;
                } else {
                    valid = false;
                }
                if(!valid) {
                    throw new InvalidParameterException("invalid unit config");
                }
            }
            fmt = fmtVal == FORMAT ? s : null;
        } else {
            fmt = "YYYY/MM/dd HH:mm:ss";
        }
        
        this.outputFmt = fmt;
        this.outputFmtVal = fmtVal;
        this.inputUnit = inputUnit;
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        long utc = ValParser.parseLong(paras[0].get(req, resp), 0);
        TimeZone tz = TimeZone.getDefault();
        int offset = ValParser.parseInt(paras[1].get(req, resp), 0) * 60000;
        tz.setRawOffset(offset);
        if(this.inputUnit > 1) {
            utc *= this.inputUnit;
        } else if(this.inputUnit == UNIT_MONTH) {
            int year = (int)(utc / 12);
            int month = (int)(utc % 12);
            Calendar c = Calendar.getInstance(tz); //按UTC 0时区算
            c.set(Calendar.YEAR, year);
            c.set(Calendar.MONTH, month);
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            utc = c.getTimeInMillis();
        } else if(this.inputUnit == UNIT_YMD) { //整型数，yyyyMMdd
            int year = (int)(utc / 10000);
            utc %= 10000;
            int month = (int)(utc / 100);
            int date = (int)(utc % 100);
            Calendar c = Calendar.getInstance(tz); //按UTC 0时区算
            c.set(Calendar.YEAR, year);
            c.set(Calendar.MONTH, month - 1);
            c.set(Calendar.DAY_OF_MONTH, date);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            utc = c.getTimeInMillis();
        }

        return formatTime(this.outputFmtVal, this.outputFmt, utc, tz);
    }
    
    static int parseFmt(String fmt) {
        int fmtVal;
        String f = fmt.toLowerCase();

        if(f.startsWith(FMT_UNIT)) {
            String s = f.substring(FMT_UNIT.length()).trim();
            if(!s.matches("\\d+")) {
                throw new InvalidParameterException("invalid unit fmt config");
            }
            fmtVal = Integer.parseInt(s);
        } else if(f.equals(FMT_MONTHS)){
            fmtVal = MONTHS;
        } else if(f.equals(FMT_MONTH)){
            fmtVal = MONTH;
        } else if(f.equals(FMT_DAYOFMONTH)){
            fmtVal = DAYOFMONTH;
        } else if(f.equals(FMT_DAYOFYEAR)){
            fmtVal = DAYOFYEAR;
        } else if(f.equals(FMT_HEX)){
            fmtVal = HEX;
        } else if(f.equals(FMT_BASE64)){
            fmtVal = BASE64;
        } else if(f.equals(FMT_WEEKSTART)){
            fmtVal = WEEKSTART;
        } else if(f.equals(FMT_WEEKEND)){
            fmtVal = WEEKEND;
        } else if(f.equals(FMT_MONTHSTART)){
            fmtVal = MONTHSTART;
        } else if(f.equals(FMT_MONTHEND)){
            fmtVal = MONTHEND;
        } else { //format string,'yyyy/MM/dd HH:mm:ss'
            fmtVal = FORMAT;
        }
        return fmtVal;
    }

    
    static Object formatTime(int fmtVal, String fmt, long utc, TimeZone tz) {
        if(fmtVal == NONE) {
            return utc; //最常见情况，放在第一个if中
        }

        //unitxxx，通常用于统计场景，比如按天汇总，时间除以86400000
        if(fmtVal > 0) {
            return utc / fmtVal;
        }

        if(fmtVal == HEX) { //long表示成16进制字符串
            return ByteUtil.long2Hex(utc, Long.SIZE, false);
        }

        if(fmtVal == BASE64) {//long表示成64进制字符串
            return ByteUtil.long2Base64(utc, Long.SIZE, false);
        }

        Calendar cal = Calendar.getInstance(tz);
        cal.setTime(new Date(utc));
        //公元0年1月到现在的月份数
        //比如1971.2返回1971*12+(2-1)=23653，从0开始
        if(fmtVal == MONTHS) {
            return cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH);
        }

        if(fmtVal == DAYOFMONTH) { //可以使用"MM"格式输出日期，此处1月返回0
            return cal.get(Calendar.DAY_OF_MONTH) - 1;
        }

        if(fmtVal == DAYOFYEAR) { //UTC指定日期在当前年度中第几天，第一天返回0
            return cal.get(Calendar.DAY_OF_YEAR) - 1;
        }
        
        if(fmtVal == MONTH) { //月份，1月返回0
            return cal.get(Calendar.MONTH);
        }

        if(fmtVal == MONTHSTART) { //UTC指定月度的1号00:00:00
            cal.set(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        }

        if(fmtVal == MONTHEND) { //UTC指定月度的下个月1号00:00:00
            cal.add(Calendar.MONTH, 1);
            cal.set(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        }
        
        if(fmtVal == WEEKSTART) { //UTC指定星期第一天(星期天)00:00:00
            cal.set(Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek()); //根据系统设置，选择星期的第一天，而不是固定为SUNDAY
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        }

        if(fmtVal == WEEKEND) { //UTC指定星期的下个星期第一天00:00:00
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            cal.add(Calendar.DAY_OF_WEEK, 1);
            return cal.getTimeInMillis();
        }

        if(fmt != null) {
            return DateUtil.utcToLocale(utc, fmt, tz);
        }

        return utc;        
    }
}
