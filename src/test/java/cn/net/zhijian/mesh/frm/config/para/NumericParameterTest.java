package cn.net.zhijian.mesh.frm.config.para;

import static cn.net.zhijian.UnitTestBase.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.prot.http.HttpServerRequest4Test;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.MapBuilder;

public class NumericParameterTest {
    @Test
    public void testIntParameter() {
        Map<String, Object> params = MapBuilder.of("v", 1);
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"v\",\"type\":\"int\",\"max\":100,\"min\":1}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        params.put("v", 1001);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("v", -1);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
    }
    
    @Test
    public void testIntParameterOptions() {
        Map<String, Object> params = MapBuilder.of("v", 1);
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"v\",\"type\":\"int\",\"max\":100,\"min\":1, \"options\":[1,2,3]}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok && v.v.equals(1));
        params.put("v", 5);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
    }
    
    @Test
    public void testFloatParameter() {
        Map<String, Object> params = MapBuilder.of("v", 1.0f);
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"v\",\"type\":\"float\",\"max\":100.0,\"min\":1.0}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        params.put("v", 1001.0f);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("v", -1.0f);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
    }
    
    @Test
    public void testDoubleParameter() {
        Map<String, Object> params = MapBuilder.of("v", 1.0);
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"v\",\"type\":\"double\",\"max\":100.0,\"min\":1.0}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        params.put("v", 1001.0);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("v", -1.0);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
    }
    
    @Test
    public void testIntBiggerOrSmallerThan() {
        Map<String, Object> params = MapBuilder.of("v1", 0, "v2", 1000, "v", 1);
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"v\",\"type\":\"int\",\"biggerThan\":\"v1\",\"smallerThan\":\"v2\"}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        params.put("v", 1000);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("v", -1);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
    }
    
    @Test
    public void testFloatBiggerOrSmallerThan() {
        Map<String, Object> params = MapBuilder.of("v1", 0.0f, "v2", 1000.0f, "v", 1.0f);
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"v\",\"type\":\"float\",\"biggerThan\":\"v1\",\"smallerThan\":\"v2\"}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        params.put("v", 1001.0f);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("v", -1.0f);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
    }
    
    @Test
    public void testDoubleBiggerOrSmallerThan() {
        Map<String, Object> params = MapBuilder.of("v1", 0.0, "v2", 1000.0, "v", 1.0);
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"v\",\"type\":\"double\",\"biggerThan\":\"v1\",\"smallerThan\":\"v2\"}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        params.put("v", 1000.0);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("v", -1.0);
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
    }
}
