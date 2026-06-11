package cn.net.zhijian.mesh.frm.config;

import java.io.File;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.UnitTestBase;

/**
 * 
 * @author flyinmind of csdn.net
 *
 */
public class ChannelConfigTest extends UnitTestBase {
    private static final String channelConfig = "{\n"
            + "\"tcpChecker\":\"cn.net.zhijian.mesh.frm.config.TestTcpChecker\",\n"
            + "\"isGateway\":true,\n"
            + "\"httpPort\":8080,\n"
            + "\"tcpPort\":8081\n"
            + "}";
    private static File saveFile;

    @BeforeAll
    public static void testInit() {
        saveFile = AbsPlatform.createTempFile("channel.cfg");
        FileUtil.writeFile(saveFile, channelConfig, IConst.DEFAULT_CHARSET);
        try {
            ChannelConfig.parse(saveFile);
        } catch (MeshException e) {
            e.printStackTrace();
        }
    }

    @AfterAll
    public static void testOver() {
        FileUtil.remove(saveFile);
        loadConfigs(configDir); //将单例恢复成从配置文件加载，否则会影响部分用例
    }

    @Test
    public void testParseWithPrivateKey() throws MeshException {
        ChannelConfig cc = ChannelConfig.instance();

        try {
            assertTrue(cc != null);
            assertEquals(cc.httpPort, 8080);
            assertTrue(cc.isGateway);
            assertTrue(cc.tcpPort > 0);
            assertTrue(cc.tcpChecker instanceof TestTcpChecker);
        } catch (Exception e) {
            fail(e.getLocalizedMessage());
        }
    }
}
