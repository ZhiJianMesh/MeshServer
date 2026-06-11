package cn.net.zhijian.mesh.frm.config.placeholder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.prot.http.HttpServerRequest4Test;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.Calculator;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.util.ValParser;

/**
 * 
 * @author flyinmind of csdn.net
 *
 */
public class ScriptElementTest extends UnitTestBase {
    @Test
    public void testNormal() {
        String s = "@{a.a},@{a.a1},@{c}";
        Map<String, Object> params = new HashMap<>();
        params.put("a", MapBuilder.of("a", "b", "a1", 2));
        params.put("c", "x");
        Map<String, Object> resp = new HashMap<>();
        Set<String> pl = new HashSet<>(params.keySet());
        pl.add("a.a");
        pl.add("a.a1");
        
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, pl, "'", "''");
        assertNotNull(segs);
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "b,2,x");
    }
    
    @Test
    public void testHashMode() {
        String s = "@{HASHMOD|16,test}";
        String v = StringUtil.uuid();
        Map<String, Object> params = new HashMap<>();
        params.put("test", v);
        Map<String, Object> resp = new HashMap<>();
        
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        int r =  v.hashCode();
        r = ValParser.absInt(r) % 16;
        assertEquals(str, Integer.toString(r));
    }
    
    @Test
    public void testRandom() {
        String s = "@{RANDOM|i,0,10}";
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        int r =  ValParser.parseInt(str, 0);
        assertTrue(r >=0 && r < 10);
        
        s = "@{RANDOM|f}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        float f =  ValParser.parseFloat(str, 0.0f);
        assertTrue(f >= 0 && f < 1.0);
        
        s = "@{RANDOM|d}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        double d =  ValParser.parseDouble(str, 0.0);
        assertTrue(d >= 0 && d < 1.0);
        
        s = "@{RANDOM|c,'A',F}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        char ch =  ValParser.parseChar(str, ' ');
        assertTrue(ch >= 'A' && ch < 'F');
        
        s = "@{RANDOM|s,10}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str.length(), 10);
        for(char c : str.toCharArray()) {
            int v = ByteUtil.getBase64CharVal(c);
            assertTrue(v >= 0 && v < 64);
        }
        
        s = "@{RANDOM|string, 10, 32}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str.length(), 10);
        for(char c : str.toCharArray()) {
            int v = ByteUtil.getBase32CharVal(c);
            assertTrue(v >= 0 && v < 32);
        }
        
        s = "@{RANDOM|s, 10,16}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str.length(), 10);
        for(char c : str.toCharArray()) {
            int v = ByteUtil.getHexCharVal(c);
            assertTrue(v >= 0 && v < 16);
        }
    }
    
    @Test
    public void testNoPlaceHolderEnd() {
        String s = "@{HASHMOD|16,test}@{testNoEnd";
        String v = StringUtil.uuid();
        Map<String, Object> params = new HashMap<>();
        params.put("test", v);
       
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        assertNull(segs);
    }

    @Test
    public void testListSeg() {
        String s = "@{p0},[@{LIST|p1,``}],[@{LIST|p2.a,`'`}],[@{LIST|p2.seg}],SIZE:@{SIZE|p1}";
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        params.put("p0", "1");
        List<Integer> l = new ArrayList<>();
        l.add(1);
        l.add(2);
        params.put("p1", l);
        List<Map<String, Object>> l1 = new ArrayList<>();
        for(int i = 0; i < 4; i++) {
            Map<String, Object> m = new HashMap<>();
            m.put("a", "'" + i);
            m.put("b", i);
            m.put("seg", i+1);
            l1.add(m);
        }
        params.put("p2", l1);

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str,"1,[1,2],['''0','''1','''2','''3'],['1','2','3','4'],SIZE:2");

        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "", null);
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "1,[1,2],['0,'1,'2,'3],[1,2,3,4],SIZE:2");

        s = "[@{LIST|p2.b,'}]";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "", null);
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "['0','1','2','3']");
    }

    @Test
    public void testListCol() {
        String s = "[@{LIST|p1.1,``}],[@{LIST|p2.seg,``}]";
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        List<List<Integer>> l = new ArrayList<>();
        l.add(Arrays.asList(1,2,3));
        l.add(Arrays.asList(2,3));
        l.add(Arrays.asList(100,30,999));
        params.put("p1", l);
        List<Map<String, Object>> l1 = new ArrayList<>();
        for(int i = 0; i < 4; i++) {
            Map<String, Object> m = new HashMap<>();
            m.put("a", "'" + i);
            m.put("b", i);
            m.put("seg", i+1);
            l1.add(m);
        }
        params.put("p2", l1);

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str,"[2,3,30],[1,2,3,4]");
    }
    
    @Test
    public void testElement() {
        List<Integer> p1 = new ArrayList<>();
        p1.add(1);
        p1.add(2);
        Map<String, Object> p2 = MapBuilder.of("a", 1, "b", "test", "c", MapBuilder.of("a", "test", "b", 1));
        Map<String, Object> params = MapBuilder.of("p1", p1, "p2", p2, "sn", 1);
        Map<String, Object> resp = MapBuilder.of("seg", "c.a");

        String s = "A:@{ELEMENT|p1,sn},B:@{ELEMENT|p1,0},C:@{ELEMENT|p2,'a'},D:@{ELEMENT|p2,!seg}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str,"A:2,B:1,C:1,D:test");
    }

    @Test
    public void testArrayAndMap() {
        String s = "P1:@{p1},P2:@{p2},P3:@{p3}";
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        List<Integer> numList = new ArrayList<>();
        numList.add(1);
        numList.add(2);
        params.put("p1", numList);

        List<String> strList = new ArrayList<>();
        strList.add("1");
        strList.add("a'b");
        params.put("p2", strList);

        Map<String, Object> d = new HashMap<>();
        d.put("d", "d");
        d.put("e", "e");
        params.put("p3", d);

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "P1:[1,2],P2:[\"1\",\"a''b\"],P3:{\"d\":\"d\",\"e\":\"e\"}");
    }

    @Test
    public void testIfNull() {
        String s = "A:`@{p0}`,B:@{IFNULL|p1,null},C:@{IFNULL|p2,null},D:@{IFNULL|p3,null,num},E:@{IFNULL|p3,p4},F:@{IFNULL|p3,p5,num}";
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        params.put("p0", "don't");
        params.put("p1", null);
        params.put("p2", "sleep\"");
        params.put("p3", null);
        params.put("p4", "a");
        params.put("p5", 4);
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "", null);
        assertTrue(segs != null);
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:`don't`,B:null,C:sleep\",D:null,E:a,F:4");

        List<String> l = new ArrayList<>();
        l.add("ab");
        l.add("c\"d");
        params.put("p1", l);
        params.put("p3", 3);

        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "\"", "\\\"");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:`don't`,B:\"[\\\"ab\\\",\\\"c\\\\\"d\\\"]\",C:\"sleep\\\"\",D:3,E:\"3\",F:3");

        l.add("e'f");
        s = "A:\"@{p0}\",B:@{IFNULL|p1,null,str}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:\"don''t\",B:'[\"ab\",\"c\\\"d\",\"e''f\"]'");
    }
    
    @Test
    public void testBracketInStr() {
        String s = "A:`@{p0}`,B:@{IFNULL|p1,`{}`},C:@{IFNULL|p2,`{}`,obj},D:@{IFNULL|p3,null,num}";
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        params.put("p0", "don't");
        params.put("p1", null);
        params.put("p2", "sleep\"");
        params.put("p3", null);
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "", null);
        assertTrue(segs != null);
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:`don't`,B:{},C:sleep\",D:null");

        List<String> l = new ArrayList<>();
        l.add("ab");
        l.add("c\"d");
        params.put("p1", l);
        params.put("p3", 3);

        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "\"", "\\\"");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:`don't`,B:\"[\\\"ab\\\",\\\"c\\\\\"d\\\"]\",C:sleep\\\",D:3");

        l.add("e'f");
        s = "A:\"@{p0}\",B:@{IFNULL|p1,null,str}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:\"don''t\",B:'[\"ab\",\"c\\\"d\",\"e''f\"]'");
    }
    
    @Test
    public void testIfValid() {
        Map<String, Object> params = MapBuilder.of("p0", "don't", "p1", null, "p2", "sleep");
        Map<String, Object> resp = new HashMap<>();
        List<String> l = List.of("ab", "c\"d", "e'f");
        params.put("p1", l);
        params.put("p3", false);
        params.put("p4", true);
        
        String s = "@{IFVALID|p0,p0, ' ', p2}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "", null);
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "don't sleep");

        params.put("p5", null);
        s = "@{IFVALID|p5,p0, ' ', p2}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "", null);
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "");

        s = "@{IFVALID|p3,p0, ' ', p2}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "", null);
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "");
        
        s = "@{IFVALID|p4, p0, ' ', p2, ` ''`}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "don''t sleep ''");
        
        s = "@{IFVALID|p4,`@{p0} @{p2} ''`}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "don''t sleep ''");
    }

    @Test
    public void testClean() {
        String s = "A:`@{CLeaN|p0}`";
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> d = new HashMap<>();
        d.put("a", "123");
        d.put("b", "456");
        params.put("p0", d);

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "", null);
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:`123 456 `");
    }
    
    @Test
    public void testClear() {
        String s = "A:@{CLEAR|a,'a\\0\\t\\n'},B:@{CLear|b,`\\0\\t\\n`},C:@{CLear|!c,'\\0\\r\\n'},D:@{CLEAR|!d,'\\0\\r\\n\\1'}";
        Map<String, Object> params = MapBuilder.of("a", "ab\0c\nd", "b", "\0cd\tef\n");
        String abnormalStr = "ab"+ Character.valueOf((char)0) + "cd" + Character.valueOf((char)1);
        Map<String, Object> resp = MapBuilder.of("c", "efg\rh", "d", abnormalStr);
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "", null);
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:bcd,B:cdef,C:efgh,D:abcd");
    }

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
    public void testCalculateDecPlaces() { //小数点后保留位数
        String s = "A:@{CALCULATE|f.3,`@{p2}/@{p1}+8`},"
                + "B:@{CALCULATE|d.6,`@{p1}*@{p2}-2.5999+8`},"
                + "C:@{CALCULATE|d.4,'(',p3,'-',p1,')/',p1},"
                + "D:@{CALCULATE|d.0,'9+(',p3,'-',p1,')/',p1}";
        Map<String, Object> params = MapBuilder.of("p1", 3.3, "p2", 1, "p3", 3);
        Map<String, Object> resp = new HashMap<>();
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        //保留0位时，转字符串后，后面仍然要跟".0"
        assertEquals(str, "A:8.303,B:8.7001,C:-0.0909,D:9.0");
    }
    
    @Test
    public void testSplit() {
        String s = "A:@{SPLIT|p1,2,`,`}++B:@{SPLIT|p2,1,`,`}++C:@{SPLIT|!p1,3,`,`}++D:@{SPLIT|!p1,5,`,`}";
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        params.put("p1", "testck");
        params.put("p2", "test");
        resp.put("p1","test");
        
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "", "\0");
        String str = segementsToStr(params, resp, segs);
        assertEquals("A:te,st,ck++B:t,e,s,t++C:tes,t++D:test", str);
        
        s = "A:@{SPLIT|p1,`;`,`,`}++B:@{SPLIT|p2,`;`,`,`}++C:@{SPLIT|!p1,`;`,`&`}++D:@{SPLIT|!p1,5,`,`}";
        params.put("p1", "test;ck");
        params.put("p2", ";test");
        resp.put("p1","test;");
        
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "'");
        str = segementsToStr(params, resp, segs);
        assertEquals("A:'test','ck'++B:'','test'++C:'test'++D:'test;'", str);
    }
    
    @Test
    public void testStrPart() {
        String s = "A:@{STRPART|p1,`,`,0},B:@{STRPART|p2,`_`,1},"
                + "C:@{STRPART|!p1,`,`,5},D:@{STRPART|!p1,`,`,10},"
                + "E:@{STRPART|!p1,`,`,-1}";
        Map<String, Object> params = MapBuilder.of("p1", "test,ck", "p2", "test_1_2");
        Map<String, Object> resp = MapBuilder.of("p1","test,1,2,3,4,5");
        
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "", "\0");
        String str = segementsToStr(params, resp, segs);
        assertEquals("A:test,B:1,C:5,D:,E:5", str);
    }
    
    @Test
    public void testStrReplace() {
        String s = "A:@{REPLACE|p1,r,!w},B:@{REPLACE|p2,`#`,'+'},"
                + "C:@{REPLACE|!p1,`,`,'/'},D:@{REPLACE|!p1,`,`,!w}";
        Map<String, Object> params = MapBuilder.of("p1", "test%ck", "p2", "test#_1_2", "r", "%");
        Map<String, Object> resp = MapBuilder.of("p1", "test,1,2", "w", "#%");
        
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "", "\0");
        String str = segementsToStr(params, resp, segs);
        assertEquals("A:test#%ck,B:test+_1_2,C:test/1/2,D:test#%1#%2", str);
    }
    
    @Test
    public void testString() {
        String s = "A:'@{UPPER|p1}',B:'@{LOWER|p2}',C:'@{SUBSTR|p1,1,10}',D:'@{SUBSTR|p1,1,2}',E:@{substr|p1,2}";
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        params.put("p1", "a'be");
        params.put("p2", "Adb?");
        params.put("p3", null);
        Set<String> paraKeys = params.keySet();

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:'A''BE',B:'adb?',C:'''be',D:'''b',E:be");

        s = "A:`@{CONCAT|p1,'-', p2}`,B:`@{COALESCE|p1,p2,p3}`,C:`@{COALESCE|p3,p2,p1}`";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:`a''be-Adb?`,B:`a''be`,C:`Adb?`");
    }
    
    @Test
    public void testJson() {
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> sub = new HashMap<>();
        sub.put("a", 1);
        sub.put("b", "xx'x");
        
        Map<String, Object> params = MapBuilder.of("p1", sub, "p2", null);
        Set<String> paraKeys = params.keySet();
        
        String s = "'@{JSON|p1}'";

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals("'{\"a\":1,\"b\":\"xx''x\"}'", str);
        
        s = "'@{JSON|p2,`{}`}'"; //not exists, use default
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals("'{}'", str);
        
        s = "'@{JSON|p1,`{}`, `'`, `|`}'";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals("'{\"a\":1,\"b\":\"xx|x\"}'", str);
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
    public void testSecure() {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        String p1 = "a'be";
        params.put("p1", p1);
        params.put("p2", "Adb?");
        params.put("p3", null);
        Set<String> paraKeys = params.keySet();

        String s = "A:@{SHA256|p1,p2},C:@{md5|p1}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:2kbXns0RmsKc0wBquu8jruR1wUnVc8plhviP6BZSiG1,C:QA7uNor2PSdHx_0q16RuW2");

        s = "UUID64:@{UUID}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str.length(), 29);

        s = "UUID16:@{UUID|16}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str.length(), 39);

        s = "HMACSHA256:@{HMACSHA256|p1,p2}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str.length(), 75);

        s = "pbkdf1:@{PBKDF|p1,p2}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        assertTrue(segs != null && segs.length > 0);
        str = segementsToStr(params, resp, segs);
        assertEquals(str.length(), 54);

        s = "pbkdf2:@{PBKDF|2,p1,p2}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str.length(), 54);
        
        s = "@{PBKDF|6,p1}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String kdf = segementsToStr(params, resp, segs);
        
        params.put("kdf", kdf);
        s = "@{PBKDFCHECK|p1,kdf}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "true");
    }
    
    @Test
    public void testEcKeyPairNoPwd() throws Exception {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        String p1 = "abc";
        params.put("p1", p1);
        Set<String> paraKeys = params.keySet();
        
        String s = "@{ECKEYPAIR}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        assertTrue(segs != null && segs.length > 0);
        String kp = segementsToStr(params, resp, segs);
        params.put("kp", kp);
        
        EccKeyPair rawKp = EccKeyPair.parse(kp);
        s = "@{ECKEYPAIR|encode,kp,p1}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String encP1 = segementsToStr(params, resp, segs);
        s = "@{ECKEYPAIR|sign,kp,p1}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String signP1 = segementsToStr(params, resp, segs);
        s = "A:@{ECKEYPAIR|public,kp}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        assertTrue(segs != null && segs.length > 0);
        s = segementsToStr(params, resp, segs);
        assertEquals(s, "A:" + rawKp.publicKey2Str());
        params.put("eP1", encP1);
        params.put("sP1", signP1);
        s = "A:@{ECKEYPAIR|decode,kp,eP1},"
            + "B:@{ECKEYPAIR|verify,kp,p1,sP1},"
            + "C:@{ECKEYPAIR|verify,kp,'abdcd',sP1}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        assertTrue(segs != null && segs.length > 0);
        s = segementsToStr(params, resp, segs);
        assertEquals(s, "A:" + p1 + ",B:true,C:false");        
    }

    @Test
    public void testEcKeyPairWithPwd() throws Exception {
        Map<String, Object> params = new HashMap<>();
        String pwd = "dll##434llll223cf";
        Map<String, Object> resp = Map.of("pwd", pwd);
        String p1 = "abc";
        params.put("p1", p1);
        Set<String> paraKeys = params.keySet();
        
        String s = "@{ECKEYPAIR|new,!pwd}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        assertTrue(segs != null && segs.length > 0);
        String kp = segementsToStr(params, resp, segs);
        params.put("kp", kp);
        
        EccKeyPair rawKp = EccKeyPair.parse(kp, pwd);
        s = "@{ECKEYPAIR|encode,kp,p1,!pwd}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String encP1 = segementsToStr(params, resp, segs);
        s = "@{ECKEYPAIR|sign,kp,p1,!pwd}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String signP1 = segementsToStr(params, resp, segs);
        s = "A:@{ECKEYPAIR|public,kp,!pwd}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        assertTrue(segs != null && segs.length > 0);
        s = segementsToStr(params, resp, segs);
        assertEquals(s, "A:" + rawKp.publicKey2Str());
        params.put("eP1", encP1);
        params.put("sP1", signP1);
        s = "A:@{ECKEYPAIR|decode,kp,eP1,!pwd},"
            + "B:@{ECKEYPAIR|verify,kp,p1,sP1,!pwd},"
            + "C:@{ECKEYPAIR|verify,kp,'abdcd',sP1,!pwd}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        assertTrue(segs != null && segs.length > 0);
        s = segementsToStr(params, resp, segs);
        assertEquals(s, "A:" + p1 + ",B:true,C:false");        
    }

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
        String fmt = "YYYY/MM/dd HH:mm:ss";
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
        String fmt = "YYYY/MM/dd HH:mm:ss";
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

        params.put("t", 1726891493938L/60000); //东八区2024-09-21 12:04:53
        s = "@{UTC|t,offset,WEEKEND,60000}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        str = DateUtil.utcToLocale(Long.parseLong(str), fmt, tz);
        System.out.println("WEEKEND:" + str);
        assertEquals(str, "2024/09/22 00:00:00");
        
        params.put("t", 1726891493938L); //东八区2024-09-21 12:04:53
        params.put("offset", offset - 60); //东7区
        s = "@{UTC|t,offset,`"+fmt+"`}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "2024/09/21 11:04:53");
        
        params.put("t", 20240921);
        params.put("offset", -120);
        s = "@{UTC|t,offset,'yyyyMMdd',ymd}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "20240921");
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

    @Test
    public void testHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("p1", "b'cd");
        headers.put("p2", "Def&");

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        params.put("p1", "a'be");
        params.put("p2", "Adb?");
        params.put("p3", null);
        Set<String> paraKeys = params.keySet();

        String s = "A:'@{UPPER|^p1}',B:'@{^p2}',C:'@{SUBSTR|p1,1,10}',D:'@{SUBSTR|^p1,1,2}',E:@{substr|^p2,2}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(headers, params, resp, segs);
        assertEquals(str, "A:'B''CD',B:'Def&',C:'''be',D:'''c',E:f&");

        s = "A:`@{CONCAT|^p1,'-', ^p2}`,B:`@{COALESCE|^p1,^p2,p3}`,C:`@{COALESCE|^p3,^p4,^p1}`";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(headers, params, resp, segs);
        assertEquals(str, "A:`b''cd-Def&`,B:`b''cd`,C:`b''cd`");
    }
    
    @Test
    public void testInjectionAttack() {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        String p1 = "a' or '1'='1";
        params.put("p1", p1);
        params.put("p2", "b' or ''='");
        Set<String> paraKeys = params.keySet();

        String s = "select id from user where account='@{p1}' and pwd='@{p2}'";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "select id from user where account='a'' or ''1''=''1' and pwd='b'' or ''''='''");
    }
    
    @Test
    public void testImageFile() {
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> params = new HashMap<>();
        params.put("v", "data:image/png;base64,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");
        params.put("rootdir", UnitTestBase.getHomeDir());
        
        String s = "@{BASE64IMG|v,'/a.png',rootdir,base64}";

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        assertNotNull(segs);
        String str = segementsToStr(params, resp, segs);
        assertEquals("", str);
    }

    @Test
    public void testSequenceHolder() {
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
    }

    @Test
    public void testHashHolder() {
        String s = "@{ABSHASH|`tes,t1`,p1,!p1,'test2'}";
        Map<String, Object> resp = new HashMap<>();
        resp.put("p1", "test4");
        Map<String, Object> params = new HashMap<>();
        params.put("p1", "test0");
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        assertTrue(segs != null && segs.length == 1);
        assertTrue(segs[0] instanceof ABSHASH);
        
        assertNotNull(segs);
        String str = segementsToStr(params, resp, segs);
        assertTrue(str.matches("\\d+"));
    }
    
    @Test
    public void testUrlCode() {
        String s = "@{URL|encode,v,'&b=1'}";
        String v = "你好 abc%a=dd中国\"你好\"'你好'";
        Map<String, Object> params = MapBuilder.of("v", v);
        Map<String, Object> resp = new HashMap<>();
        
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        System.out.println("urlencode:" + str);
        assertEquals(str, "%E4%BD%A0%E5%A5%BD+abc%25a%3Ddd%E4%B8%AD%E5%9B%BD%22%E4%BD%A0%E5%A5%BD%22%27%E4%BD%A0%E5%A5%BD%27%26b%3D1");
        resp.put("p1", str);
        s = "@{URL|decode, !p1}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, v + "&b=1");
    }

    @Test
    public void testUrlAppend() {
        String s = "@{URL|append, url, 'a', a, 'b', b}";
        String url = "/1.1.1.1:8523";
        Map<String, Object> params = MapBuilder.of("url", url, "a", 22, "b", "test");
        Map<String, Object> resp = new HashMap<>();
        
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "/1.1.1.1:8523?a=22&b=test");
        resp.put("p1", "/add/");
        params.put("url", "/aa?c=1");
        s = "@{URL|append, url, 'p1', !p1, 'a', a}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "/aa?c=1&p1=%2Fadd%2F&a=22");
    }
    
    @Test
    public void testCondition() {
        String s = "A:@{CONDITION|4,`i.>`,3,'1','0'},"
                + "B:@{CONDITION|s1,'s.<',s2,'ok','nok'},"
                + "C:@{CONDITION|d1,'d.>',d2,'ok',s1},"
                + "D:@{CONDITION|f1,'f.>=',f2,'ok','nok'},"
                + "E:@{CONDITION|f1,f.==,f2,'ok','nok'},"
                + "F:@{CONDITION|f1,'f.<=',f2,'ok','nok'},"
                + "G:@{CONDITION|f1,'f.>',f2,'ok','nok'},"
                + "H:@{CONDITION|f1,'f.!=',f2,'ok','nok'},"
                + "I:@{CONDITION|f1,'f.<',f2},"
                + "J:@{CONDITION|i1,'i.>=',i2,'B','S'},"
                + "K:@{CONDITION|i2,'i.>=',i1,!h,'S'}";
        Map<String, Object> params = new HashMap<>();
        params.put("s1", "n1");
        params.put("s2", "n2");
        params.put("d1", 2.4);
        params.put("d2", 3.3);
        params.put("f1", 2.4);
        params.put("f2", 2.4);
        params.put("i1", 1);
        params.put("i2", 6);
        
        Map<String, Object> resp = MapBuilder.of("h", "test");

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:1,B:ok,C:n1,D:ok,E:ok,F:ok,G:nok,H:nok,I:0,J:S,K:test");
    }

    @Test
    public void testConditionMatch() {
        String s = "A:@{CONDITION|s1,`s.~`,`n\\d+`,'true','false'},"
                + "B:@{CONDITION|s2,'s.!~',`n\\d{2}`},"
                + "C:@{CONDITION|'windows17','s.~',`windows1\\d`}";
        Map<String, Object> params = MapBuilder.of("s1", "n1", "s2", "n2");
        Map<String, Object> resp = new HashMap<>();

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:true,B:1,C:1");
        s = "A:@{CONDITION|s1,`i.~`,`n\\d+`,'true','false'},"
            + "B:@{CONDITION|s2,'s.!~',`n\\d{2}`}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        assertTrue(segs == null);
    }
    
    @Test
    public void testConditionIn() {
        String s = "A:@{CONDITION|s1,`s.@`,`a,b,c `,'true','false'},"
                + "B:@{CONDITION|s2,'s.!@',`a,b,c`},"
                + "C:@{CONDITION|'b','s.@',`a,b,c`},"
                + "D:@{CONDITION|s3,'s.@',`a,b,c`},"
                + "E:@{CONDITION|2,'i.@',`1,2 ,4`},"
                + "F:@{CONDITION|2.0,'i.@',`1.5,2.0,4`}";
        Map<String, Object> params = MapBuilder.of("s1", "a", "s2", "c ", "s3", "d");
        Map<String, Object> resp = new HashMap<>();

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:true,B:1,C:1,D:0,E:0,F:1");
    }
    
    @Test
    public void testConditionNull() {
        String s = "A:@{CONDITION|o1,`o.==`,null,true,false},"
                + "B:@{CONDITION|o1, 'o.!=', o2, o1, o2},"
                + "C:@{CONDITION|'windows17','o.==',`windows17`,'ok', 'nok'},"
                + "D:@{CONDITION|!o3, 'o.==', null, 'isnull', 'notnull'},"
                + "E:@{CONDITION|null, 'o.!=', !o3, 'notnull', 'isnull'},"
                + "F:@{CONDITION|!o4, 'o.==', !o3, 'true', 'false'},"
                + "G:@{CONDITION|o, 'o.==', o1, 'true', 'false'},"
                + "H:@{CONDITION|!o5, 'o.==', null, true, false},"
                + "I:@{CONDITION|o2, 'o.==', null, true, false}";
        Map<String, Object> params = MapBuilder.of("o1", "n1", "o2", null, "o", "n1");
        Map<String, Object> resp = MapBuilder.of("o3", new Object(), "o4", null);

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:false,B:n1,C:ok,D:notnull,E:notnull,F:false,G:true,H:true,I:true");
    }

    @Test
    public void testConditionWithOthers() {
        String s = "A:@{CONDITION|s1,'s.<',s2, `@{CALCULATE|d.1,d1,'+',d2}`,d2},"
                + "B:@{CONDITION|4,`i.>`,3, `@{s1}`,s2},"
                + "C:@{CONDITION|d1,'d.>',d2, !r1, s1},"
                + "D:@{CONDITION|d1,'d.>',d2, s1, `@{!r1} @{!r2}`},"
                + "E:@{CONDITION|!r1,'s.==',!r2, `@{CALCULATE|d.1,d1,'+',d2}`,d2}";
        Map<String, Object> params = new HashMap<>();
        params.put("s1", "n1");
        params.put("s2", "n2");
        params.put("d1", 2.4);
        params.put("d2", 3.3);
       
        Map<String, Object> resp = MapBuilder.of("r1", "hello", "r2", "world");

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:5.7,B:n1,C:n1,D:hello world,E:3.3");
    }
    
    @Test
    public void testSeqidStep() { //CONDITION非常重要的一个使用场景
        String s = "@{SWITCH|!interval,'l.<',5000, !step, '*2',\n"
                + "|, !interval, 'l.<', 10000, !step,\n"
                + "|, !step, 'i.>', 10, !step, '/2',\n"
                + "|, !step}";
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = MapBuilder.of("interval", 100001, "step", 20);
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "20/2");
        
        resp = MapBuilder.of("interval", 500, "step", 10);
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "10*2");
        
        resp = MapBuilder.of("interval", 8000, "step", 10);
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "10");

        resp = MapBuilder.of("interval", 100002, "step", 10);
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "10");
    }
    
    @Test
    public void testFor() {
        String s = "A:@{FOR|el,`+`,'(',i,`,'`,e,`',@{!r})`}"
                + "B:@{FOR|list,`,`,'(',i,`,`,e.a,`,`,e.b,`,@{p},'','')`};"
                + "C:@{FOR|list,`,`,'(',i,`,`,e.a,`,`,e.b,`,`,e.c,')'};"
                + "D:@{FOR|list,`+`,'(',i,`,`,e.a,`,`,e.b,`,`,!r,')'};"
                + "E:@{FOR|list,`+`,'(',i,`,`,p,`,`,e.b,`,`,!r,')'}";
        Map<String, Object> o1 = MapBuilder.of("a", "n1", "b", "n2", "c", 1);
        Map<String, Object> o2 = MapBuilder.of("a", "m1", "b", "m2", "c", 2);
        List<Map<String, Object>> list = Arrays.asList(o1, o2);
        List<Object> l = Arrays.asList("1", 2);
        Map<String, Object> params = MapBuilder.of("list", list, "p", "x", "el", l);
        Map<String, Object> resp = MapBuilder.of("r", "test");

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:(0,'1',test)+(1,'2',test)"
                + "B:(0,n1,n2,x,'',''),(1,m1,m2,x,'','');"
                + "C:(0,n1,n2,1),(1,m1,m2,2);"
                + "D:(0,n1,n2,test)+(1,m1,m2,test);"
                + "E:(0,x,n2,test)+(1,x,m2,test)");
    }
    
    @Test
    public void testForWithOthers() {
        String s = "A:@{FOR|list,`;`,'(',i,`,`,e.a,`,`,e.b,`,@{IFVALID|p1,p1,'+',p2},'','')`}"
                + "B:@{FOR|list,`,`,'(',i,`,`,e.a,`,`,e.b,`,`,e.c,`,@{CONDITION|p1,'s.==', 'x', 'true', 'false'})`};";
        Map<String, Object> o1 = MapBuilder.of("a", "n1", "b", "n2", "c", 1);
        Map<String, Object> o2 = MapBuilder.of("a", "m1", "b", "m2", "c", 2);
        List<Map<String, Object>> list = Arrays.asList(o1, o2);
        Map<String, Object> params = MapBuilder.of("list", list, "p1", "x", "p2", "y");
        Map<String, Object> resp = MapBuilder.of("r", "test");

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:(0,n1,n2,x+y,'','');(1,m1,m2,x+y,'','')"
                + "B:(0,n1,n2,1,true),(1,m1,m2,2,true);");
    }
    
    @Test
    public void testForWithFilters() {
        String s = "A:@{FOR|list[[e.a,'s.==','n1']||e.b,'s.==','m2'],`;`,'(',i,`,`,e.a,`,`,e.b,`,@{IFVALID|p1,p1,'+',p2},'','')`}"
                + "B:@{FOR|list[e.a, 's.!=', 'n1'],`,`,'(',i,`,`,e.a,`,`,e.b,`,`,e.c,`,@{CONDITION|p1,'s.==', 'x', 'true', 'false'})`};"
                + "C:@{FOR|!ol[e, 's.!=', '1' && [i,'i.>',0]],`,`,'(',i,`,'`,e,`')`};"
                + "D:@{FOR|!ol[e,'s.!=',#tokenAcc],`,`, i,`#`, e}";
        Map<String, Object> o1 = MapBuilder.of("a", "n1", "b", "n2", "c", 1);
        Map<String, Object> o2 = MapBuilder.of("a", "m1", "b", "m2", "c", 2);
        List<Object> ol = Arrays.asList(new Object[] {"2",1,"1","test"});
        List<Map<String, Object>> list = Arrays.asList(o1, o2);
        Map<String, Object> params = MapBuilder.of("list", list, "p1", "x", "p2", "y");
        params.put("#tokenAcc", "1");
        
        Map<String, Object> resp = MapBuilder.of("r", "test", "ol", ol);

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:(0,n1,n2,x+y,'','');(1,m1,m2,x+y,'','')"
                + "B:(1,m1,m2,2,true);"
                + "C:(3,'test');"
                + "D:0#2,3#test");
    }
    
    @Test
    public void testSwitchComplex() {
        String s = "A:@{SWITCH|i1,`i.>`,i2,'1',|,s1,'s.<=',s2,`0,2,@{s1}`|,'0','1',s1},"
                + "B:@{SWITCH|i1,`i.>`,i2,'1',|,s1,'s.>=',s2,'0',2,s1|,'0','1',s2},"
                + "C:@{SWITCH|i1,`i.<`,i2,'1',`''`,|,s1,'s.<=',s2,'0',2,s1|,'0','1',s2},"
                + "D:@{SWITCH|i1,`i.>`,i2,'1',|,s1,'s.<=',s2,'0',2,s1|,'0','1',s2}";
        Map<String, Object> params = MapBuilder.of("s1", "n1", "s2", "n2", "i1", 1);
        params.put("i2", 6);
        Map<String, Object> resp = MapBuilder.of("h", "test");

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:0,2,n1,B:01n2,C:1'',D:02n1");
    }
    
    @Test
    public void testSwitchSimple() {
        String s = "A:@{SWITCH|4,`i.>`,3,'1',|,'0'},"
                + "B:@{SWITCH|s1,'s.<',s2,'1',|,'2','3'},"
                + "C:@{SWITCH|d1,'d.>',d2,'ok',|,s1},"
                + "D:@{SWITCH|f1,'f.>=',f2,'ok',|,'nok'},"
                + "E:@{SWITCH|f1,f.==,f2,s1,'&',|,s2},"
                + "F:@{SWITCH|f1,'f.<=',f2,'ok',|,'nok'},"
                + "G:@{SWITCH|f1,'f.>',f2,'ok',|,'nok'},"
                + "H:@{SWITCH|f1,'f.!=',f2,'ok',|,'nok'},"
                + "I:@{SWITCH|f1,'f.<',f2,s1,|,s2},"
                + "J:@{SWITCH|i1,'i.>=',i2,'B',|,'S'},"
                + "K:@{SWITCH|i2,'i.>=',i1,!h,|,'S'},"
                + "L:@{SWITCH|s1,'o.==',null,!h,|,'S'}";
        Map<String, Object> params = new HashMap<>();
        params.put("s1", "n1");
        params.put("s2", "n2");
        params.put("d1", 2.4);
        params.put("d2", 3.3);
        params.put("f1", 2.4);
        params.put("f2", 2.4);
        params.put("i1", 1);
        params.put("i2", 6);
        
        Map<String, Object> resp = new HashMap<>();
        resp.put("h", "test");

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:1,B:1,C:n1,D:ok,E:n1&,F:ok,"
                + "G:nok,H:nok,I:n2,J:S,K:test,L:S");
    }
    
    @Test
    public void testMaxMin() {
        String s = "A:@{MAX|i,l1},B:@{MIN|l,l1},"
                + "C:@{MIN|f,l2.a},D:@{MAX|d,l2.a},"
                + "E:@{MIN|i,l3.0},F:@{MAX|l,l3.0}";
        List<Object> l1 = Arrays.asList(2,1,3,-1,5,7);
        List<Object> l2 = Arrays.asList(MapBuilder.of("a",1,"b", 2),MapBuilder.of("a",2),MapBuilder.of("a",-1,"b",9));
        List<Object> l3 = Arrays.asList(Arrays.asList(1,4,6),Arrays.asList(7,9,3),Arrays.asList(-9,10,6));
        Map<String, Object> params = MapBuilder.of("l1", l1, "l2", l2, "l3", l3);
        Map<String, Object> resp = new HashMap<>();

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:7,B:-1,C:-1.0,D:2.0,E:-9,F:7");
    }
    
    @Test
    public void testSum() {
        String s = "A:@{SUM|i,l1},B:@{SUM|l,l2.a},C:@{SUM|l,l2.b},"
                + "D:@{SUM|d.2,l3.1},E:@{SUM|f.2,l3.0}";
        List<Object> l1 = Arrays.asList(2,1,3,-1,5,7);
        List<Object> l2 = Arrays.asList(MapBuilder.of("a",1,"b", 2),MapBuilder.of("a",2),MapBuilder.of("a",-1,"b",9));
        List<Object> l3 = Arrays.asList(Arrays.asList(1.51,4.84,6),Arrays.asList(7.9,9,3),Arrays.asList(-9,10,6));
        Map<String, Object> params = MapBuilder.of("l1", l1, "l2", l2, "l3", l3);
        Map<String, Object> resp = new HashMap<>();

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:17,B:2,C:11,D:23.84,E:0.41");
    }
    
//    @Test
//    public void compareConditionAndJs() {
//        String s = "@{CONDITION|d1,'d.>',d2,1,0}";
//        String js="@{d1}>@{d2}";
//        Map<String, Object> params = new HashMap<>();
//        params.put("d1", 2.4);
//        params.put("d2", 3.3);
//        
//        Map<String, Object> resp = new HashMap<>();
//
//        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
//        ScriptElement[] segJs = ScriptElement.parsePlaceHolder(js, params.keySet(), "'", "''");
//        long start = System.currentTimeMillis();
//        for(int i = 0; i < 1000; i++) {
//            segementsToStr(params, resp, segs);
//        }
//        long end = System.currentTimeMillis();
//        System.out.println("speed:" + 1000L * 1000 / (end - start)); //142858/s
//        start = System.currentTimeMillis();
//        for(int i = 0; i < 1000; i++) {
//            String jsStr = segementsToStr(params, resp, segJs);
//            JsEngine.getBool(jsStr);
//        }
//        end = System.currentTimeMillis();
//        System.out.println("speed:" + 1000L * 1000 / (end - start)); //725/s
//        //assertEquals(str, "A:1,B:ok,C:n1,D:ok,E:ok,F:ok,G:nok");
//    }
    @Test
    public void testWhenCondition() {
        String s, str;
        double v;
        ScriptElement[] segs;
        
        Map<String, Object> params = new HashMap<>();
        params.put("a", 1);
        params.put("b", "100");
        
        Map<String, Object> resp = new HashMap<>();
        resp.put("a", 2);
        resp.put("b", "101");
        
        s = "@{CONDITION|a,'i.>',!a}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        v = Calculator.calculate(str);
        assertEquals(v, 0.0, 0);

        s = "@{CONDITION|a,'s.<',!a}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        v = Calculator.calculate(str);
        assertTrue(v > 0);

        s = "@{CONDITION|a,'i.>',!a}+@{CONDITION|b,'i.<',!b}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        v = Calculator.calculate(str);
        assertTrue(v > 0);
        
        s = "@{CONDITION|a,'i.>',!a}*@{CONDITION|b,'i.<',!b}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        v = Calculator.calculate(str);
        assertEquals(v, 0.0, 0);
        
        s = "(@{CONDITION|a,'i.>',!a}*@{CONDITION|b,'i.<',!b})"
            + "   \n+ @{CONDITION|a,'i.<',!b}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        v = Calculator.calculate(str);
        assertTrue(v > 0);
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
    public void testRequestResponse() {
        Map<String, Object> params = MapBuilder.of("p0", "don't", "p1", 1, "p2", "sleep");
        Map<String, Object> resp = Map.of("r0",0, "r1", "test");
        String s = "@{request},@{response}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "", null);
        String str = segementsToStr(params, resp, segs);
        assertTrue(str.indexOf(IConst.EMBEDED_REQUESTAT) >= 0);
        assertTrue(str.indexOf("\"p0\":\"don't\"") >= 0);
        assertTrue(str.indexOf("\"r1\":\"test\"") >= 0);
        assertTrue(str.indexOf("\"p2\":\"sleep\"") >= 0);
    }

    private String segementsToStr(Map<String, String> headers, Map<String, Object> params, Map<String, Object> resp, ScriptElement[] segs) {
        StringBuilder sb = new StringBuilder();
        AbsServerRequest req = HttpServerRequest4Test.create(testService, headers, params);
        for(ScriptElement se : segs) {
            sb.append(se.run(req, resp));
        }
        String str = sb.toString();
        System.out.println(str);
        return str;
    }

    private String segementsToStr(Map<String, Object> params, Map<String, Object> resp, ScriptElement[] segs) {
        return segementsToStr(new HashMap<>(), params, resp, segs);
    }
}
