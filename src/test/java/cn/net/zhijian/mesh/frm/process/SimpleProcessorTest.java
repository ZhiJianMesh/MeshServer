package cn.net.zhijian.mesh.frm.process;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.para.ParameterInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.prot.http.HttpServerRequest4Test;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 
 * @author flyinmind of csdn.net
 *
 */
public class SimpleProcessorTest extends UnitTestBase implements IConst {
    ParameterInfo[] paras = new ParameterInfo[] {
        new ParameterInfo.Builder("as", ParameterInfo.TYPE_STRING).build(),
        new ParameterInfo.Builder("v", ParameterInfo.TYPE_STRING).build(),
    };
    RequestInfo ri = new RequestInfo(paras);
    UrlPathInfo url = new UrlPathInfo("/testprocess");
    ApiInfo apiInfo = new ApiInfo("root", IConst.METHOD_GET, url, "", false);
    
    @Test
    public void testStaticProcess() throws InterruptedException, ExecutionException {
        StaticProcessor proc = new StaticProcessor(testService, apiInfo, "testLogic");
        Map<String, Object> cfg = JsonUtil.jsonToMap("{\n"
                + "\"name\": \"return_default\",\n"
                + "\"type\": \"static\",\n"
                + "\"when\":\"@{CONDITION|!errorCode, 'i.==', 2001}\",\n"
                + "\"data\":\"{\\\"@{as}\\\":\\\"@{v}\\\"}\"\n"
                + "}");
        boolean r = proc.parse(url, cfg, ri);
        assertTrue(r);
        
        Map<String, String> headers = MapBuilder.of(HEAD_CID, "40");
        Map<String, Object> params = MapBuilder.of("as", "test", "v", "vvv");
        AbsServerRequest req = HttpServerRequest4Test.create(testService, headers, params);
        Map<String, Object> respData = MapBuilder.of(IConst.CFG_ERROR_CODE, 2001);
        HandleResult hr = proc.handleAll(req, respData).get();
        
        assertEquals(hr.code, RetCode.OK);
        String v = ValParser.getAsStr(hr.data, "test", null);
        assertTrue(v != null && v.equals("vvv"));
        respData.put(IConst.CFG_ERROR_CODE, RetCode.OK);
        hr = proc.handleAll(req, respData).get();
        assertEquals(hr.code, RetCode.OK);
        assertTrue(!hr.data.containsKey("test"));
    }
    
    @Test
    public void testLogicProcess() throws InterruptedException, ExecutionException {
        LogicProcessor proc = new LogicProcessor(testService, apiInfo, "testLogic");
        Map<String, Object> cfg = JsonUtil.jsonToMap("{\n"
                + "\"name\": \"return_default\",\n"
                + "\"type\": \"logic\",\n"
                + "\"condition\":\"@{CONDITION|!errorCode, 'i.==', 2001}\",\n"
                + "\"success\":\"{\\\"@{as}\\\":\\\"@{v}\\\"}\",\n"
                + "\"errorCode\":10000,\"errorInfo\":\"fatal\""
                + "}");
        boolean r = proc.parse(url, cfg, ri);
        assertTrue(r);
        
        Map<String, String> headers = MapBuilder.of(HEAD_CID, "40");
        Map<String, Object> params = MapBuilder.of("as", "test", "v", "vvv");
        AbsServerRequest req = HttpServerRequest4Test.create(testService, headers, params);
        Map<String, Object> respData = MapBuilder.of(IConst.CFG_ERROR_CODE, 2001);
        HandleResult hr = proc.handleAll(req, respData).get();
        
        assertEquals(hr.code, RetCode.OK);
        String v = ValParser.getAsStr(hr.data, "test", null);
        assertTrue(v != null && v.equals("vvv"));
        respData.put(IConst.CFG_ERROR_CODE, RetCode.OK);
        hr = proc.handleAll(req, respData).get();
        assertEquals(hr.code, 10000);
        assertTrue(hr.data.size() == 0);
    }
    
    @Test
    public void testJsProcess() throws InterruptedException, ExecutionException {
        JSProcessor proc = new JSProcessor(testService, apiInfo, "testJs");
        Map<String, Object> cfg = JsonUtil.jsonToMap("{\n"
                + "\"name\": \"return_default\",\n"
                + "\"type\": \"js\",\n"
                + "\"script\":\"Mesh.success({\\\"@{as}\\\":\\\"@{v}\\\"})\""
                + "}");
        boolean r = proc.parse(url, cfg, ri);
        assertTrue(r);
        
        Map<String, String> headers = MapBuilder.of(HEAD_CID, "40");
        Map<String, Object> params = MapBuilder.of("as", "test", "v", "vvv");
        AbsServerRequest req = HttpServerRequest4Test.create(testService, headers, params);
        Map<String, Object> respData = MapBuilder.of(IConst.CFG_ERROR_CODE, 2001);
        HandleResult hr = proc.handleAll(req, respData).get();
        
        assertEquals(hr.code, RetCode.OK);
        String v = ValParser.getAsStr(hr.data, "test", null);
        assertTrue(v != null && v.equals("vvv"));
        params.put("as", "test1");
        hr = proc.handleAll(req, respData).get();
        assertEquals(hr.code, RetCode.OK);
        assertTrue(hr.data.containsKey("test1"));
        
        cfg = JsonUtil.jsonToMap("{\n"
                + "\"name\": \"return_default\",\n"
                + "\"type\": \"js\",\n"
                + "\"script\":\"{\\\"@{as}\\\":\\\"@{v}\\\"}\"" //直接返回json串
                + "}");
        r = proc.parse(url, cfg, ri);
        assertTrue(r);
        hr = proc.handleAll(req, respData).get();
        assertEquals(hr.code, RetCode.OK);
        v = ValParser.getAsStr(hr.data, "test1", null);
        assertTrue(v != null && v.equals("vvv"));
    }
    
    @Test
    public void testVarProcess() throws InterruptedException, ExecutionException {
        VarProcessor proc = new VarProcessor(testService, apiInfo, "testVar");
        Map<String, Object> cfg = JsonUtil.jsonToMap("{\n"
                + "\"name\": \"return_default\",\n"
                + "\"type\": \"var\",\n"
                + "\"vars\":[\n"
                + " {\"name\":\"v\", \"val\":\"@{v}\", \"toResp\":true}"
                + "]"
                + "}");
        boolean r = proc.parse(url, cfg, ri);
        assertTrue(r);
        
        Map<String, String> headers = MapBuilder.of(HEAD_CID, "40");
        Map<String, Object> params = MapBuilder.of("as", "test", "v", "vvv");
        AbsServerRequest req = HttpServerRequest4Test.create(testService, headers, params);
        Map<String, Object> respData = MapBuilder.of(IConst.CFG_ERROR_CODE, 2001);
        HandleResult hr = proc.handleAll(req, respData).get();
        
        assertEquals(hr.code, RetCode.OK);
        String v = ValParser.getAsStr(respData, "v", null);
        assertTrue(v != null && v.equals("vvv"));

        v = ValParser.getAsStr(params, "v", null);
        assertTrue(v != null && v.equals("vvv"));
    }
    
    //var产生一个数组
    @Test
    public void testVarsProcess() throws InterruptedException, ExecutionException {
        VarProcessor proc = new VarProcessor(testService, apiInfo, "testVar");
        Map<String, Object> cfg = JsonUtil.jsonToMap("{\n"
                + "\"name\": \"return_default\",\n"
                + "\"type\": \"var\",\n"
                + "\"vars\":[\n"
                + " {\"name\":\"vl\", \"list\":4, \"val\":\"@{v}\", \"toResp\":true}"
                + "]"
                + "}");
        boolean r = proc.parse(url, cfg, ri);
        assertTrue(r);
        
        Map<String, String> headers = MapBuilder.of(HEAD_CID, "40");
        Map<String, Object> params = MapBuilder.of("as", "test", "v", "vvv");
        AbsServerRequest req = HttpServerRequest4Test.create(testService, headers, params);
        Map<String, Object> respData = MapBuilder.of(IConst.CFG_ERROR_CODE, 2001);
        HandleResult hr = proc.handleAll(req, respData).get();
        
        assertEquals(hr.code, RetCode.OK);
        List<Object> v = ValParser.getAsList(respData, "vl");
        assertTrue(v != null && v.size() == 4 && v.get(0).equals("vvv"));

        v = ValParser.getAsList(params, "vl");
        assertTrue(v != null && v.size() == 4 && v.get(0).equals("vvv"));
    }
}
