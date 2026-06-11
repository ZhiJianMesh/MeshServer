package cn.net.zhijian.mesh.frm.abs;

import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor.OnSuccess;
import cn.net.zhijian.mesh.prot.http.HttpServerRequest4Test;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.ValParser;

public class AbsProcessorTest extends UnitTestBase {
    @Test
    public void testOnSuccessRs() {
        Map<String, String> headers = MapBuilder.of("cid", "40");
        Map<String, Object> params = MapBuilder.of("price", 1, "comment", "don't");
        Map<String, Object> resp = MapBuilder.of("turn", 2);
        AbsServerRequest req = HttpServerRequest4Test.create(headers, params);
        String cfgStr = "@{SWITCH|!turn,'i.==',0,`{code:1,info:\"test\"}`,"
                + "|,!turn,'i.==',1,`{code:2,info:\"@{comment}\"}`,"
                + "|,`{code:0,info:\"Success\",data:{price:@{price}}}`"
                + "}";
        
        OnSuccess onSuccess = OnSuccess.parse(cfgStr, params.keySet());
        HandleResult hr = onSuccess.run(req, resp);
        assertEquals(hr.code, 0);
        assertEquals(ValParser.getAsDouble(hr.data, "price"), 1.0, 0.1);
        resp.put("turn", 1);
        hr = onSuccess.run(req, resp);
        assertEquals(hr.code, 2);
        resp.remove("turn");
        hr = onSuccess.run(req, resp);
        assertEquals(hr.code, 1);
    }
    
    @Test
    public void testOnSuccessJson() {
        Map<String, String> headers = MapBuilder.of("cid", "40");
        Map<String, Object> params = MapBuilder.of("price", 1, "comment", "don't");
        Map<String, Object> resp = MapBuilder.of("turn", 1);
        AbsServerRequest req = HttpServerRequest4Test.create(headers, params);
        String cfgStr = "{code:0,info:\"Success\",data:{price:@{price}}}";
        
        OnSuccess onSuccess = OnSuccess.parse(cfgStr, params.keySet());
        HandleResult hr = onSuccess.run(req, resp);
        assertEquals(hr.code, RetCode.OK);
        assertEquals(ValParser.getAsDouble(hr.data, "price"), 1.0, 0.1);
        resp.put("turn", 0);
        hr = onSuccess.run(req, resp);
        assertEquals(hr.code, RetCode.OK);
    }
    
    @Test
    public void testOnSuccessJs() {
        Map<String, String> headers = MapBuilder.of("cid", "40");
        Map<String, Object> params = MapBuilder.of("price", 1, "comment", "don't");
        Map<String, Object> resp = MapBuilder.of("turn", 1);
        AbsServerRequest req = HttpServerRequest4Test.create(headers, params);
        String cfgStr = "(function(){\n"
                + "if('@{!turn}'=='') {//没查到，说明没有参与签字\n"
                + "   return Mesh.error(RetCode.NO_RIGHT, 'not authorized');\n"
                + "}\n"
                + "return Mesh.success({price:@{price}});\n"
                + "})()";
        
        OnSuccess onSuccess = OnSuccess.parse(cfgStr, params.keySet());
        HandleResult hr = onSuccess.run(req, resp);
        assertEquals(hr.code, RetCode.OK);
        assertEquals(ValParser.getAsDouble(hr.data, "price"), 1.0, 0.1);
        resp.remove("turn");
        hr = onSuccess.run(req, resp);
        assertEquals(hr.code, RetCode.NO_RIGHT);
    }
    
    @Test
    public void testOnSuccessMap() {
        Map<String, String> headers = MapBuilder.of("cid", "40");
        Map<String, Object> params = MapBuilder.of("price", 1, "comment", "don't");
        Map<String, Object> resp = MapBuilder.of("turn", 1);
        AbsServerRequest req = HttpServerRequest4Test.create(headers, params);
        String cfgStr = "{errorCode:\"RetCode.NO_RIGHT\",errorInfo:\"no right\","
                + "condition:\"@{CONDITION|price,'i.==',1}\"}";
        Map<String, Object> cfg = JsonUtil.jsonToMap(cfgStr);
        
        OnSuccess onSuccess = OnSuccess.parse(cfg, params.keySet());
        HandleResult hr = onSuccess.run(req, resp);
        assertEquals(hr.code, RetCode.OK);
        params.put("price", 0.0);
        hr = onSuccess.run(req, resp);
        assertEquals(hr.code, RetCode.NO_RIGHT);
        assertEquals(hr.info, "no right");
    }
}
