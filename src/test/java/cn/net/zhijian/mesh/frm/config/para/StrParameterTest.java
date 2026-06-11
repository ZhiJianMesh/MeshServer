package cn.net.zhijian.mesh.frm.config.para;

import static cn.net.zhijian.UnitTestBase.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.prot.http.HttpServerRequest4Test;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.MapBuilder;

public class StrParameterTest {
    @Test
    public void testStringRegular() {
        Map<String, Object> params = MapBuilder.of("b", "test-12345");
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"b\",\"type\":\"string\",\"regular\":\"^test-\\\\d+\"}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
    }
    
    @Test
    public void testStringMaps() {
        Map<String, Object> params = MapBuilder.of("b", "abc");
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"b\",\"type\":\"string\",\"maps\":{\"abc\":\"ABC\"}}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        assertTrue(v.v.equals("ABC"));
        params.put("b", "A");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        assertTrue(v.v.equals("A"));
    }
    
    @Test
    public void testStringOptions() {
        Map<String, Object> params = MapBuilder.of("b", "abc");
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"b\",\"type\":\"string\",\"options\":[\"abc\", \"ABC\"]}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        params.put("b", "A");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
    }
}
