package cn.net.zhijian.mesh.prot.http;

import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.UnitTestBase;

public class NullServerRequestTest extends UnitTestBase {
    @Test
    public void testNullServer() {
        int cid = CompanyInfo.instance().id;
        try {
            AccessToken token = testService.tokenWorker.create(
                    PartitionConfig.instance().partition, IConst.SERVICE_BACKEND,
                    testService.name, "", IOAuth.TOKENTYPE_SERVICE);
            Map<String, String> headers = MapBuilder.of(IConst.HEAD_CID, "" + cid);
            Map<String, Object> params = MapBuilder.of("a", 1);
            AbsServerRequest req = new NullServerRequest(
                    IConst.METHOD_GET, "init",
                    headers, params, testService, token);
            AbsServerResponse resp = req.createResponse();
            assertEquals(req.cid(), cid);
            assertEquals(req.getInt("a"), 1);
            resp.end(null);
        } catch(Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }
}
