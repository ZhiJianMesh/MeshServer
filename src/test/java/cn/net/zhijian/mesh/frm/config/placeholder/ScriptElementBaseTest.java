package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 
 * @author flyinmind of csdn.net
 *
 */
public class ScriptElementBaseTest extends ScriptElementTestUtil {
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
    public void testNoPlaceHolderEnd() {
        String s = "@{HASHMOD|16,test}@{testNoEnd";
        String v = StringUtil.uuid();
        Map<String, Object> params = new HashMap<>();
        params.put("test", v);
       
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        assertNull(segs);
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
}
