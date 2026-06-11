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

public class IPParameterTest {
    @Test
    public void testIPParameterCheck() {
        Map<String, Object> params = MapBuilder.of("v", "192.168.0.01");
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"v\",\"type\":\"ip\",\"must\":true,\"format\":\"WAN|V4|PORT\"}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("v", "[240e:3af:c40:a332:15aa:6e61:319b:e8ea]:8080");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("v", "8.8.8.8:8080");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"v\",\"type\":\"ip\",\"must\":true,\"format\":\"lan|V4\"}");
        p = ParameterInfo.parse(strCfg, false);
        params.put("v", "192.168.0.01");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        params.put("v", "2.168.0.01");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("v", "192.168.0.01:8080");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        params.put("v", "240e:3af:c40:a332:15aa:6e61:319b:e8ea");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        
        strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"test\",\"type\":\"ip\",\"format\":\"lan|V4|LIST\"}");
        p = ParameterInfo.parse(strCfg, false);
        params.put("test", "192.168.0.01,10.11.1.1");
        v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
    }
    
    @Test
    public void testIPParameterList() {
        Map<String, Object> params = MapBuilder.of("v", List.of("192.168.0.01","192.168.0.02"));
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        Map<String, Object> strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"v\",\"type\":\"ip\",\"list\":true,\"must\":true,\"format\":\"WAN|V4|PORT\"}");
        ParameterInfo p = ParameterInfo.parse(strCfg, false);
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);

        strCfg = JsonUtil.jsonToMap(
                "{\"name\":\"v\",\"type\":\"ip\",\"list\":true,\"format\":\"lan|V4|LIST\"}");
        p = ParameterInfo.parse(strCfg, false);
        params.put("v", List.of("192.168.0.01,10.11.1.1","192.168.0.02,10.11.1.2"));
        v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
  
        params.put("v", List.of("192.168.0.01","192.168.0.02,10.11.1.2"));
        v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
    }
}
