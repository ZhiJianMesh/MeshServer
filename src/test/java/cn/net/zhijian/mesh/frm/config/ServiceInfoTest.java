package cn.net.zhijian.mesh.frm.config;

import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.UnitTestBase;

public class ServiceInfoTest extends UnitTestBase {
    private static final String json = "{\n"
            + "    \"author\":\"flyinmind@zhijian.net.cn\",\n"
            + "    \"version\":\"0.1.0\",\n"
            + "    \"level\":0,\n"
            + "    \"type\":\"CLOUD\",\n"
            + "    \"displayName\":\"服务仓库\",\n"
            + "    \"dependencies\" : [\n"
            + "        {\"name\":\"seqid\", \"minVersion\":\"0.1.0\", \"features\":\"*\"}\n"
            + "    ]\n"
            + "}";
    @Test
    public void testServiceCfgParse() {
        Map<String, Object> cfg = JsonUtil.jsonToMap(json);
        ServiceInfo si = ServiceInfo.parse("home", "test", cfg, null, false);
        assertTrue(si != null);
        assertEquals(si.name, "test");
        AccessToken token = si.tokenWorker.create(
                PartitionConfig.instance().partition, IConst.SERVICE_BACKEND,
                si.name, "", IOAuth.TOKENTYPE_SERVICE);
        assertTrue(token != null);
    }
}
