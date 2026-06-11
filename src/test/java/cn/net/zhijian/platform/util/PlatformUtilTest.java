package cn.net.zhijian.platform.util;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.UnitTestBase;

public class PlatformUtilTest extends UnitTestBase {
    @Test
    public void testIsLocalIp() {
        String localIp = AbsPlatform.myIp();
        System.out.println("local ip:" + localIp);
        assertTrue(AbsPlatform.isLocalIp(localIp));
    }
}
