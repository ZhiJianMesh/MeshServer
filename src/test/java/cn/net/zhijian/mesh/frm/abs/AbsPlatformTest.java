package cn.net.zhijian.mesh.frm.abs;

import static cn.net.zhijian.UnitTestBase.assertTrue;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.util.IPUtil;

public class AbsPlatformTest {
    @Test
    public void testGlobalIpv6() throws MeshException {
        String ipv6 = AbsPlatform.globalIPv6();
        assertTrue(ipv6 != null && !IPUtil.isLanIp(ipv6));
    }
}
