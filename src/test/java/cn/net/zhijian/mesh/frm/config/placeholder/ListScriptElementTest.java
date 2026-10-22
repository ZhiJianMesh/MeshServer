package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.util.MapBuilder;

public class ListScriptElementTest extends ScriptElementTestUtil {

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
}
