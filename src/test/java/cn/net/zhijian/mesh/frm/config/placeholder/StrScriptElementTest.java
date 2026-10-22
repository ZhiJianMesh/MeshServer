package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.ValParser;

public class StrScriptElementTest extends ScriptElementTestUtil {
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
    public void testStringPad() {
        String s = "A:'@{STRPAD|p1,5,`-`}',B:'@{STRPAD|p1,`0`,5}',C:'@{STRPAD|p2,`0`,5}',D:'@{STRPAD|p2,5,`0`}',E:'@{STRPAD|p3,`-`,6}',F:'@{STRPAD|p4,`0`,6}'";
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        params.put("p1", "ab'c");
        params.put("p2", "ab'cdefghi");
        params.put("p3", null);
        params.put("p4", 123);
        Set<String> paraKeys = params.keySet();

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:'ab''c-',B:'0ab''c',C:'efghi',D:'ab''cd',E:'------',F:'000123'");
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
}
