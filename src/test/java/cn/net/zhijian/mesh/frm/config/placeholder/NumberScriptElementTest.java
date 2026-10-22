package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

public class NumberScriptElementTest extends ScriptElementTestUtil {
    @Test
    public void testCalculate() {
        String s = "A:@{ADD|i,1,2},B:@{ADD|double,p1,2},C:@{SUB|l,20,1},D:@{multi|double,p1,p2},F:@{DIV|float,p1,p2}";
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        params.put("p1", 3);
        params.put("p2", 1);

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:3,B:5.0,C:19,D:3.0,F:3.0");
        
        s = "@{SUB|long,#reqAt,2500}";
        Set<String> availableParas = new HashSet<>(params.keySet());
        //segementsToStr中创建ServerRequest时会填写#reqAt，
        //如果此处添加到params中，在安卓中运行较慢的情况下，会导致两处不一致
        //此处添加参数名称，保证解析能够通过
        availableParas.add("#reqAt");
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        long cur = ValParser.getAsLong(params, "#reqAt");

        assertEquals(str, Long.toString(cur - 2500));
    }
    
    @Test
    public void testCalculateSimplePlaces() {
        String s = "A:@{ADD|i,1,2},B:@{ADD|double.3,p1,2},C:@{SUB|l.2,20,1},D:@{multi|double.1,p1,p2},E:@{DIV|f.3,p1,p2}";
        Map<String, Object> params = MapBuilder.of("p1", 3.0346, "p2", 6.829999);
        Map<String, Object> resp = new HashMap<>();

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:3,B:5.035,C:19,D:20.7,E:0.444");
    }

    @Test
    public void testCalculateEnhanced() {
        String s = "A:@{CALCULATE|i,p1,'*',p2,'-',2.5,'+',8},"
                + "B:@{CALCULATE|d,`@{p1}*@{p2}-2.5+8`},"
                + "C:@{CALCULATE|d,'(',p3,'-',p2,')*',p1}";
        Map<String, Object> params = MapBuilder.of("p1", 3.3, "p2", 1, "p3", 3);
        Map<String, Object> resp = new HashMap<>();
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:8,B:8.8,C:6.6"); //可能会因为浮点数精度问题导致不通过
    }
    
    @Test
    public void testCalculateFloatPlaces() { //小数点后保留位数
        String s = "A:@{CALCULATE|f.3,`@{p2}/@{p1}+8`},"
                + "B:@{CALCULATE|d.6,`@{p1}*@{p2}-2.5999+8`},"
                + "C:@{CALCULATE|d.4,'(',p3,'-',p1,')/',p1},"
                + "D:@{CALCULATE|d.0,'9+(',p3,'-',p1,')/',!p1}";
        Map<String, Object> params = MapBuilder.of("p1", 3.3, "p2", 1, "p3", 3);
        Map<String, Object> resp = MapBuilder.of("p1", 3.3);
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        //保留0位时，转字符串后，后面仍然要跟".0"
        assertEquals(str, "A:8.303,B:8.7001,C:-0.0909,D:9.0");
    }
    
    @Test
    public void testCalculateDirFormula() { //直接的算公式，不加引号
        String s = "A:@{CALCULATE|f.3,`@{p2}/@{p1}+8+@{!p1}`},"
                + "B:@{CALCULATE|d.6,p2/p1+8},"
                + "C:@{CALCULATE|d.0,9+(p3 - p1)/ !p1}";
        Map<String, Object> params = MapBuilder.of("p1", 3.3, "p2", 1, "p3", 3);
        Map<String, Object> resp = MapBuilder.of("p1", 3.3);
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        //保留0位时，转字符串后，后面仍然要跟".0"
        assertEquals(str, "A:11.603,B:8.30303,C:9.0");
    }

    @Test
    public void testCounter() {
        String s = "A:@{COUNTER|4,'head'},B:@{counter},C:@{counter|5},D:@{counter|3,h},E:@{counter|h}";
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        params.put("h", "test");

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:head0000,B:1,C:00002,D:test003,E:test4");
    }
    
    
    @Test
    public void testInt2Ver() {
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> params = new HashMap<>();
        params.put("v", 1001);
        Set<String> paraKeys = params.keySet();
        
        String s = "@{VERCONVERT|v,tostr}";

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals("0.1.1", str);
    }
    
    
    @Test
    public void testConst() {
        int VERSION_INT = StringUtil.verToInt(IConst.ENGINEVERSION);
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> params = new HashMap<>();
        Set<String> paraKeys = params.keySet();
        
        String s = "A:@{CONST|i,max},B:@{CONST|i,min},"
                + "C:@{CONST|l,max},D:@{CONST|l,min},"
                + "E:@{CONST|f,max},F:@{CONST|f,min},"
                + "G:@{CONST|d,max},H:@{CONST|double,min},"
                + "I:@{CONST|i,ver},J:@{CONST|s,ver},"
                + "K:@{CONST|i,tzOffset}"; //东八区，其他区域需修改

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        Calendar c = Calendar.getInstance();
        assertEquals("A:2147483647,B:-2147483648,"
                + "C:9223372036854775807,D:-9223372036854775808,"
                + "E:3.4028235E38,F:1.4E-45,"
                + "G:1.7976931348623157E308,H:4.9E-324,"
                + "I:"+VERSION_INT+",J:" + IConst.ENGINEVERSION
                + ",K:" + (c.getTimeZone().getRawOffset() / 60000), str);
    }

    @Test
    public void testVer2Int() {
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> params = new HashMap<>();
        params.put("v", "12.11.123");
        Set<String> paraKeys = params.keySet();
        
        String s = "@{VERCONVERT|v}";

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals("12011123", str);
    }
    

    @Test
    public void testSequenceHolder() {
        //只是解析，并没有向sequence服务发起申请
        String s = "@{SEQUENCE|`test`}";
        Map<String, Object> params = new HashMap<>();
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        assertTrue(segs != null && segs.length == 1);
        assertTrue(segs[0] instanceof SEQUENCE);
        
        s = "@{SEQUENCE|'test'}"; //兼容性测试
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        assertTrue(segs != null && segs.length == 1);
        assertTrue(segs[0] instanceof SEQUENCE);
        
        s = "@{SEQUENCE|test}"; //兼容性测试
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        assertTrue(segs != null && segs.length == 1);
        assertTrue(segs[0] instanceof SEQUENCE);

        s = "@{SEQUENCE|i,test,100}"; //批量申请测试
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        assertTrue(segs != null && segs.length == 1);
        assertTrue(segs[0] instanceof SEQUENCE);
    }
}
