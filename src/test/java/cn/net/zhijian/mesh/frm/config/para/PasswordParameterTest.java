package cn.net.zhijian.mesh.frm.config.para;

import static cn.net.zhijian.UnitTestBase.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.prot.http.HttpServerRequest4Test;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.MapBuilder;

public class PasswordParameterTest {
    @Test
    public void testPasswordStringParameter() {
        Map<String, Object> params = MapBuilder.of("pwd", "Abc111000@A", "acc", "test-12345");
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"pwd\",\"type\":\"password\",\"rule\":\"6,2,4\"}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        params.put("pwd", "abcddddd");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("pwd", "AAA@@1111");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("pwd", "AAA@test-12345");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"pwd\",\"type\":\"password\""
                + ",\"rule\":\"6,3,4,acc\"}");
        p = ParameterInfo.parse(strCfg, false);
        params.put("pwd", "Abc111000A");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        params.put("pwd", "abcddddd");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("pwd", "AAA@@1111");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("pwd", "AAA@test-12345");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("pwd", "54321-tset@AAA");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("pwd", "AAA@54321-tset");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
    }
    
    @Test
    public void testStringEqualsTo() {
        Map<String, Object> params = MapBuilder.of("a", "test-12345", "b", "test-12345");
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"b\",\"type\":\"password\",\"equalsTo\":\"a\"}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        List<String> dd = p.dependParas();
        assertTrue(dd.size() == 1 && dd.get(0).equals("a"));
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        params.put("b", "abcddddd");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
    }
}
