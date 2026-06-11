package cn.net.zhijian.mesh.frm.config.para;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.prot.http.HttpServerRequest4Test;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.ValParser;

public class ObjParameterTest extends UnitTestBase  {
    @Test
    public void testBaseObjectParameter() {
        Map<String, Object> objCfg = JsonUtil.jsonToMap("{\"name\":\"test\",\"type\":\"object\","
            + "\"checkAll\":true,\"props\":["
            + " {\"name\":\"a\",\"type\":\"int\",\"max\":10000,\"min\":1},"
            + " {\"name\":\"b\",\"type\":\"string\",\"max\":10000,\"min\":1}"
            + "]}");
        ParameterInfo p = ParameterInfo.parse(objCfg, false);
        assertTrue(p instanceof ObjectParameterInfo);
    }
    
    @Test
    public void testParameterUseObj() {
        Map<String, Object> objCfg = JsonUtil.jsonToMap("{\"name\":\"v\",\"type\":\"int\",\"smallerThan\":\"obj.max\",\"biggerThan\":\"obj.min\"}");
        ParameterInfo p = ParameterInfo.parse(objCfg, false);
        
        Map<String, Object> o = MapBuilder.of("max", 50, "min", 10);
        Map<String, Object> params = MapBuilder.of("v", 100, "obj", o);
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("v", 9);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("v", 15);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
    }
    
    @Test
    public void testObjListTopParameter() { //引用顶层的对象进行判断
        Map<String, Object> o = MapBuilder.of("age", 50, "name", "admin");
        Map<String, Object> params = MapBuilder.of("adminName", "admin", "maxAge", 100, "o", o);
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"o\",\"type\":\"object\",\"props\":["
              + " {\"name\":\"age\",\"type\":\"int\",\"must\":true, \"smallerThan\":\"maxAge\", \"min\":1}"
              + " {\"name\":\"name\",\"type\":\"string\",\"equalsTo\":\"adminName\"}"
              + "]}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        
        o.put("age", 1000);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        o.put("age", -1);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
    }
    
    @Test
    public void testObjListSubParameter() {
        Map<String, Object> objCfg = JsonUtil.jsonToMap("{\"name\":\"v\",\"type\":\"object\","
            + "\"checkAll\":true,\"list\":true,\"props\":["
            + " {\"name\":\"start\",\"type\":\"int\",\"biggerThan\":\"start0\",\"min\":1},"
            + " {\"name\":\"end\",\"type\":\"int\",\"biggerThan\":\".start\",\"min\":1},"
            + " {\"name\":\"s1\",\"type\":\"string\",\"min\":1},"
            + " {\"name\":\"s2\",\"type\":\"password\",\"equalsTo\":\".s1\",\"min\":1}"
            + "]}");
        Map<String, Object> o1 = MapBuilder.of("start", 11, "end", 22, "s1", "t1", "s2", "t1");
        Map<String, Object> o2 = MapBuilder.of("start", 31, "end", 30, "s1", "t1", "s2", "t2");
        List<Map<String, Object>> lines = Arrays.asList(o1, o2);
        Map<String, Object> params = MapBuilder.of("start0", 15, "v", lines);
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        ParameterInfo p = ParameterInfo.parse(objCfg, false);
        
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok, v.errInfo);
        
        params.put("start0", 10);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok, v.errInfo);
        
        o2.put("end", 33);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        
        o2.put("s2", "t1");
        v = p.valueOf(req, params, params);
        assertTrue(v.ok);
    }

    @Test
    public void testArrObjectParameter() {
        Map<String, Object> o1 = MapBuilder.of("a", 1, "b", "test1");
        Map<String, Object> o2 = MapBuilder.of("a", 2, "b", "test2");
        List<Map<String, Object>> lines = Arrays.asList(o1, o2);
        Map<String, Object> params = MapBuilder.of("objArr", lines);
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);

        Map<String, Object> objCfg = JsonUtil.jsonToMap("{\"name\":\"objArr\",\"type\":\"object\","
            + "\"checkAll\":true,\"list\":true,\"must\":true,\"props\":["
            + " {\"name\":\"a\",\"type\":\"int\",\"max\":10000,\"min\":1}"
            + " {\"name\":\"b\",\"type\":\"string\",\"max\":10000,\"min\":1}"
            + "]}");

        ParameterInfo p = ParameterInfo.parse(objCfg, false);
        assertNotNull(p);
        assertTrue(p instanceof ObjectParameterInfo && p.list && p.must);
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        assertTrue(v.v instanceof List<?>);
        List<Object> arr = ValParser.parseList(v.v);
        assertNotNull(arr);
        assertEquals(arr.size(), 2);
        Map<String, Object> d1 = ValParser.parseObject(arr.get(0));
        assertNotNull(d1);
        assertTrue(d1.containsKey("a") && d1.containsKey("b"));
    }
}
