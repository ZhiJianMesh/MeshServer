package cn.net.zhijian.mesh.client;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.client.DBClient.DBReqBuilder;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.frm.intf.IDBConst;

public class ServiceReqBuilderTest extends UnitTestBase {
    @Test
    public void testServiceReqBuilderUrl() {
        ServiceReqBuilder req = new ServiceReqBuilder(testService, "callee")
                .url("/test/get")
                .appendPara("a", "1")
                .appendPara("b", "2");
        assertEquals(req.url, "/test/get?a=1&b=2");
        req.appendPara("c", "3");
        assertEquals(req.url, "/test/get?a=1&b=2&c=3");
        
        req = new ServiceReqBuilder(testService, "callee")
                .url("/test/get?c=3")
                .appendPara("a", "1")
                .appendPara("b", "2");
        assertEquals(req.url, "/test/get?c=3&a=1&b=2");
        
        Map<String, Object> params = new HashMap<>();
        params.put("a", "1");
        params.put("b", "2");
        
        req = new ServiceReqBuilder(testService, "callee")
                .url("/test/get?c=3")
                .appendParas(params);
        assertTrue(req.url.equals("/test/get?c=3&a=1&b=2")
                || req.url.equals("/test/get?c=3&b=2&a=1"));
    }
    
    @Test
    public void testRequestBody() {
        ServiceReqBuilder req = new ServiceReqBuilder(testService, "callee")
                .body("a")
                .put("a", 1)
                .put("b", 2);
        String body1 = req.body();
        assertEquals(body1, "a");
        req = new ServiceReqBuilder(testService, "callee")
                .put("a", 1)
                .put("b", 2);
        body1 = req.body();
        assertEquals(body1, "{\"a\":1,\"b\":2}");
        
        DBReqBuilder dbReq = new DBReqBuilder(testService, "db", IDBConst.LOCAL_DBNO);
        dbReq.put("a", 1).put("b", 2);
        
        String body2 = dbReq.body();
        assertTrue(body2.indexOf("\"db\"") > 0);
        System.out.println("DBReqBuilder.body:" + body2);
        
        try {
            dbReq = new DBReqBuilder(testService, "db", 0).body("t");
            fail("DBReqBuilder.body not supportted");
        } catch(UnsupportedOperationException e) {
        }
    }
}
