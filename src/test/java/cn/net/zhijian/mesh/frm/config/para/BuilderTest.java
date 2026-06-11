package cn.net.zhijian.mesh.frm.config.para;

import static cn.net.zhijian.UnitTestBase.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.prot.http.HttpServerRequest4Test;
import cn.net.zhijian.util.MapBuilder;

public class BuilderTest {
    @Test
    public void testBuilder() {
        Map<String, Object> params = MapBuilder.of("a", "12345");
        AbsServerRequest req = HttpServerRequest4Test.create(null, params);
        ParameterInfo p = new ParameterInfo.Builder("a", ParameterInfo.TYPE_STRING)
                .setRegular("^\\d+$").build();
        Value v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        
        p = new ParameterInfo.Builder("a", ParameterInfo.TYPE_INT)
                .setMax(100).build();
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        
        p = new ParameterInfo.Builder("a", ParameterInfo.TYPE_INT)
                .setMax(100000).build();
        v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
        
        p = new ParameterInfo.Builder("a", ParameterInfo.TYPE_INT)
                .setMax(100000).setMin(12346).build();
        v = p.valueOf(req, params, params);
        assertTrue(v != null && !v.ok);
        
        p = new ParameterInfo.Builder("a", ParameterInfo.TYPE_STRING)
                .setMax(100000).setMin(0).setOptions(List.of("12345", "789")).build();
        v = p.valueOf(req, params, params);
        assertTrue(v != null && v.ok);
    }
}
