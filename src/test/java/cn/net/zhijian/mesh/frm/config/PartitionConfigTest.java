package cn.net.zhijian.mesh.frm.config;

import java.io.File;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.platform.IPlatformConst;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.UnitTestBase;

/**
 * 
 * @author flyinmind of csdn.net
 *
 */
public class PartitionConfigTest extends UnitTestBase {
    private static final String partitionCfg = "{\n"
            + "\"partition\":0,\n"
            + "\"biosServers\":[\"192.168.1.4:8080\"]\n"
            + "}";

    private static File saveFile;

    @BeforeAll
    public static void testInit() throws Exception {
        saveFile = AbsPlatform.createTempFile("partcfg.cfg");
        FileUtil.writeFile(saveFile, partitionCfg, IConst.DEFAULT_CHARSET);
    }

    @AfterAll
    public static void testOver() {
        FileUtil.remove(saveFile);
        loadConfigs(configDir); //将单例恢复成从配置文件加载，否则会影响部分用例
    }

    @Test
    public void testParseWithoutPrivateKey() {
        Map<String, Object> map = JsonUtil.jsonToMap(partitionCfg);
        PartitionConfig cfg;

        try {
            cfg = PartitionConfig.parse(saveFile, map, IPlatformConst.EVM);
            NodeAddress node = cfg.mainBios();
            assertTrue(node.addr.equals("192.168.1.4:8080"));
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getLocalizedMessage());
        }

        try {
            cfg = PartitionConfig.parse(saveFile, IPlatformConst.EVM);
            NodeAddress node = cfg.mainBios();
            assertTrue(node.addr.equals("192.168.1.4:8080"));
        } catch (Exception e) {
            fail(e.getLocalizedMessage());
        }
    }
    
    @Test
    public void testSave() {
        Map<String, Object> map = JsonUtil.jsonToMap(partitionCfg);
        PartitionConfig cfg;

        try {
            cfg = PartitionConfig.parse(saveFile, map, IPlatformConst.EVM);
            cfg.save();
            cfg = PartitionConfig.parse(saveFile, IPlatformConst.EVM);
            NodeAddress node = cfg.mainBios();
            assertTrue(node.addr.equals("192.168.1.4:8080"));
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getLocalizedMessage());
        }
    }
}
