package cn.net.zhijian.mesh.frm.config.placeholder;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.ValParser;

public class TimeScriptElementTest extends ScriptElementTestUtil {
    private static final String fmt = "YYYY/MM/dd HH:mm:ss";

    @Test
    public void testNow() {
        long t0 = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        tz.setRawOffset(8 * 86400 * 1000); //东八区
        Date d0 = new Date(t0);

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        Set<String> paraKeys = params.keySet();
        params.put(IConst.EMBEDED_REQUESTAT, t0);

        String s = "@{NOW}";
        String str1;
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertTrue(ValParser.parseLong(str, 0) >= t0);

        s = "@{NOW|unit1000}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertTrue(ValParser.parseLong(str, 0) >= t0 / 1000);

        s = "@{NOW|unit86400}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertTrue(ValParser.parseLong(str, 0) >= t0 / 86400);

        s = "@{NOW|hex,89999}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertTrue(Long.valueOf(str, 16) >= t0);

        s = "@{NOW|'yyyy-MM-dd HH:mm:ss'}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        str1 = sdf.format(d0);
        assertTrue(str.compareTo(str1) >= 0);
        
        s = "@{NOW|yyyy-MM-dd HH:mm:ss}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        str1 = sdf.format(d0);
        assertTrue(str.compareTo(str1) >= 0);
        
        s = "@{NOW|yyyy-MM-dd HH:mm:ss, 25200000}"; //东7区
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        sdf.setTimeZone(tz);
        str1 = sdf.format(d0);
        assertTrue(str.compareTo(str1) < 0);
    }
    
    @Test
    public void testUtcBase() {
        int offset = 480; //东八区
        TimeZone tz = TimeZone.getDefault();
        tz.setRawOffset(offset * 60000);
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        params.put("t", 1726891493938L); //东八区2024-09-21 12:04:53
        params.put("offset", offset);
        Set<String> paraKeys = params.keySet();

        String s = "@{UTC|t,offset,`"+fmt+"`}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "2024/09/21 12:04:53");
        
        s = "@{UTC|t,offset,`unit60000`}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        str = DateUtil.utcToLocale(Long.parseLong(str)*60000, fmt, tz);
        assertEquals(str, "2024/09/21 12:04:00");
    }
    
    @Test
    public void testUtcAdvance() {
        int offset = 480; //东八区
        TimeZone tz = TimeZone.getDefault();
        tz.setRawOffset(offset * 60000);
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        params.put("t", 1726891493938L); //东八区2024-09-21 12:04:53星期六
        params.put("offset", offset);
        Set<String> paraKeys = params.keySet();
        
        String s = "@{UTC|t,offset,MONTHS}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "24296");
        
        s = "@{UTC|t,offset,MONTHSTART}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        str = DateUtil.utcToLocale(Long.parseLong(str), fmt, tz);
        assertEquals(str, "2024/09/01 00:00:00");
        
        s = "@{UTC|t,offset,MONTHEND}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        str = DateUtil.utcToLocale(Long.parseLong(str), fmt, tz);
        assertEquals(str, "2024/10/01 00:00:00");
        
        s = "@{UTC|t,offset,WEEKSTART}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        str = DateUtil.utcToLocale(Long.parseLong(str), fmt, tz);
        Calendar cal = Calendar.getInstance(tz);
        if(cal.getFirstDayOfWeek() == Calendar.SUNDAY) {
            assertEquals(str, "2024/09/15 00:00:00");
        } else {
            assertEquals(str, "2024/09/16 00:00:00");
        }

        s = "@{UTC|t,offset,WEEKEND}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        str = DateUtil.utcToLocale(Long.parseLong(str), fmt, tz);
        System.out.println("WEEKEND:" + str);
        assertEquals(str, "2024/09/22 00:00:00");

        params.put("t", 1726891493938L); //东八区2024-09-21 12:04:53
        s = "@{UTC|t,offset,DAYSTART}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        str = DateUtil.utcToLocale(Long.parseLong(str), fmt, tz);
        System.out.println("DAYSTART:" + str);
        assertEquals(str, "2024/09/21 00:00:00");
        s = "@{UTC|t,offset,DAYEND}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        str = DateUtil.utcToLocale(Long.parseLong(str), fmt, tz);
        System.out.println("DAYSTART:" + str);
        assertEquals(str, "2024/09/22 00:00:00");
        
        params.put("t", 1726891493938L); //东八区2024-09-21 12:04:53
        params.put("offset", offset - 60); //东7区
        s = "@{UTC|t,offset,`"+fmt+"`}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "2024/09/21 11:04:53");
    }
    
    @Test
    public void testUtcAndNow() {
        int offset = 480; //东八区
        TimeZone tz = TimeZone.getDefault();
        tz.setRawOffset(offset * 60000);
        Calendar cal = Calendar.getInstance(tz);

        int month = cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        int minutes = (int)(cal.getTimeInMillis() / 60000);
        
        Map<String, Object> params = MapBuilder.of("offset", offset);
        Map<String, Object> resp = new HashMap<>();
        Set<String> paraKeys = params.keySet();
        String s = "@{NOW|months}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, Integer.toString(month));
        params.put("curMonth", str);
        
        s = "@{SUB|i,curMonth,1}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, Integer.toString(month - 1));
        params.put("month", month);
        
        s = "@{UTC|month,offset,monthstart,month}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, Long.toString(cal.getTimeInMillis()));
        params.put("dayStart1", str);
        
        s = "@{UTC|dayStart1,offset,unit60000}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);

        assertEquals(str, Integer.toString(minutes));
    }
    
    @Test
    public void testUtcWithInputFmt() {
        int offset = 480; //东八区
        TimeZone tz = TimeZone.getDefault();
        tz.setRawOffset(offset * 60000);
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        params.put("t", 1726891493938L/60000); //东八区2024-09-21 12:04:53星期六
        params.put("offset", offset);
        Set<String> paraKeys = params.keySet();
        
        String s = "@{UTC|t,offset,MONTHS,unit60000}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "24296");

        params.put("t", 1726891493938L/60000); //东八区2024-09-21 12:04:53
        s = "@{UTC|t,offset,WEEKEND,unit60000}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        str = DateUtil.utcToLocale(Long.parseLong(str), fmt, tz);
        System.out.println("WEEKEND:" + str);
        assertEquals(str, "2024/09/22 00:00:00");
        
        params.put("t", 20240921);
        params.put("offset", -120);
        s = "@{UTC|t,offset,'yyyyMMdd',ymd}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "20240921");
    }
    
    @Test
    public void testNextPeriod() {
        Map<String, Object> params = MapBuilder.of("period", "D", "val", 0);
        Map<String, Object> resp = MapBuilder.of("val", 48 * 3600 * 1000);
        Set<String> paraKeys = params.keySet();

        String s = "A:@{NEXTPERIOD|period,val},B:@{NEXTPERIOD|'D',val}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        long time = System.currentTimeMillis();
        String result = Long.toString((time / 86400000 + 1) * 86400000);
        assertEquals(str, "A:" + result + ",B:" + result);
        
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        if(cal.get(Calendar.DAY_OF_WEEK) >= Calendar.TUESDAY) {
            cal.set(Calendar.DAY_OF_WEEK, Calendar.TUESDAY);
            time = cal.getTimeInMillis() + IConst.WEEK_MS;
        } else {
            cal.set(Calendar.DAY_OF_WEEK, Calendar.TUESDAY);
            time = cal.getTimeInMillis();
            if(time < System.currentTimeMillis()) {
                time += IConst.WEEK_MS;
            }
        }
        
        s = "@{NEXTPERIOD|'W',!val}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, Long.toString(time));
    }


}
