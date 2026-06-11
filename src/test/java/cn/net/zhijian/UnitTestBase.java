package cn.net.zhijian;


import java.io.File;
import java.io.InputStream;
import java.util.Map;

import org.junit.jupiter.api.Assertions;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.RootKeystore;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.abs.AbsAssets;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.js.JsEngine;
import cn.net.zhijian.platform.IPlatformConst;
import cn.net.zhijian.platform.util.AssetsWrapper;
import cn.net.zhijian.platform.util.SrvKeystoreHelper;
import cn.net.zhijian.platform.util.SrvPlatformUtil;
import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.FileUtilBase;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;

/**
 * 需要在src/test/resources/META-INF/services/org.junit.platform.launcher.TestExecutionListener
 * 中写入UnitTestBase的完整包名及类名，让所有用例执行时，首先执行它
 */
public class UnitTestBase {
    public static final int TCP_PORT = 8524;
    public static final int HTTP_PORT = 8523;
    private static final String serviceJson = "{\n"
        + "    \"author\":\"flyinmind@zhijian.net.cn\",\n"
        + "    \"version\":\"0.1.0\",\n"
        + "    \"level\":0,\n"
        + "    \"type\":\"CLOUD\",\n"
        + "    \"displayName\":\"服务仓库\",\n"
        + "    \"dependencies\" : [\n"
        + "        {\"name\":\"seqid\", \"minVersion\":\"0.1.0\", \"features\":\"*\"}\n"
        + "    ]\n"
        + "}";

    protected static final String insideAddr;
    protected static final String configDir;
    private static boolean initialized = false;
    private static final String HomeDir = FileUtilBase.addPath(System.getProperty("user.dir"), "unittest");

    protected static final String TEST_SERVICE = "testservice";
    protected static final String SERVICE_ROOT;
    protected static final String OUT_ROOT;
    protected static final ServiceInfo testService;
    protected static final ServiceInfo omSi;

    static {
        new File(HomeDir).mkdirs();
        configDir = FileUtilBase.addPath(HomeDir, IConst.SYS_CONF_DIR);
        SERVICE_ROOT = FileUtilBase.addPath(HomeDir, "services", TEST_SERVICE);
        OUT_ROOT = FileUtilBase.addPath(SERVICE_ROOT, "output");
        initLog();
        AbsAssets.init(new AssetsWrapper(UnitTestBase.class.getClassLoader()));
        ServiceInfo.setWorkDir(HomeDir);
        AbsPlatform.init(new SrvPlatformUtil(HomeDir));
        initUnitTestSystem();
        FileUtilBase.removeDir(new File(AbsPlatform.dbRoot()));
        insideAddr = /*SrvPlatformUtil.myIp() +*/ "192.168.1.6:" + HTTP_PORT;
        ServiceClient.setCloudDns(IConst.SERVICE_HTTPDNS, new String[]{"api.zhijian.net.cn:8523"}, -1);
        testService = createTestServiceInfo(SERVICE_ROOT, TEST_SERVICE);
        omSi = createTestServiceInfo("dir", IConst.SERVICE_OM);
        JsEngine.initContext();
    }
    
    private static void initLog() {
        System.setProperty("io.netty.tryReflectionSetAccessible", "true");
        copyResource("logback.xml", configDir);
        //初始化日志相关的配置，如果日志初始化失败则退出
        String logCfgFile = FileUtilBase.addPath(configDir, "logback.xml");
        String logsPath = FileUtilBase.addPath(HomeDir, "logs");
        try {
            if(!LogUtil.init(logCfgFile, logsPath, true)) {
                System.out.println("Fail to load log config file:" + logCfgFile);
            }
            RootKeystore.setHelper(new SrvKeystoreHelper(configDir));
        } catch(Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    public static synchronized void initUnitTestSystem() {
        if(initialized) {
            System.out.println("initUnitTestSystem has been called");
            return;
        }
        System.out.println("call initUnitTestSystem");
        initialized = true;
        copyResource("channel.cfg", configDir);
        copyResource("partition.cfg", configDir);
        copyResource("company.cfg", configDir);
        copyResource("root.keystore", configDir);
        copyResource("server.bks", configDir);
        copyResource("services.keystore", configDir);

        loadConfigs(configDir); 
    }

    public static String getHomeDir() {
        return HomeDir;
    }

    public static String getUserDir() {
        return System.getProperty("user.dir");
    }


    public static ServiceInfo createTestServiceInfo(String homeDir, String name) {
        Map<String, Object> cfg = JsonUtil.jsonToMap(serviceJson);
        EccKeyPair ekp = null;
        try {
            ekp = Ecc.instance().genKeyPair();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ServiceInfo.parse(homeDir, name, cfg, ekp, false);
    }

    public static void loadConfigs(String cfgPath) {
        try {
            ChannelConfig.parse(ChannelConfig.configFile(cfgPath));
        } catch (MeshException e) {
            e.printStackTrace();
        }

        try {
            PartitionConfig partCfg = PartitionConfig.parse(PartitionConfig.configFile(cfgPath), IPlatformConst.EVM);
            PartitionConfig.instance(partCfg);
            CompanyInfo.init(CompanyInfo.configFile(cfgPath));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void copyResource(String name, String dstDir) {
        try (InputStream in = UnitTestBase.class.getClassLoader().getResourceAsStream(name)) {
            FileUtilBase.copyStream(in, FileUtil.addPath(dstDir, name));
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    //屏蔽android与jvm中单元测试assert差异
    public static void assertTrue(boolean condition, String msg) {
        Assertions.assertTrue(condition, msg);
    }

    public static void assertTrue(boolean condition) {
        Assertions.assertTrue(condition);
    }

    public static void fail(String msg, Throwable e) {
        Assertions.fail(msg, e);
    }

    public static void fail(String msg) {
        Assertions.fail(msg);
    }
    
    public static void assertFalse(boolean condition) {
        Assertions.assertFalse(condition);
    }
    
    public static void assertFalse(boolean condition, String msg) {
        Assertions.assertFalse(condition, msg);
    }
    
    public static void assertNull(Object o, String msg) {
        Assertions.assertNull(o, msg);
    }
    
    public static void assertNull(Object o) {
        Assertions.assertNull(o);
    }
    
    public static void assertNotNull(Object o, String msg) {
        Assertions.assertNotNull(o, msg);
    }
    
    public static void assertNotNull(Object o) {
        Assertions.assertNotNull(o);
    }
    
    public static void assertEquals(int actual, int expected, String message) {
        Assertions.assertEquals(expected, actual, message);
    }

    public static void assertEquals(int actual, int expected) {
        Assertions.assertEquals(expected, actual);
    }
    public static void assertEquals(long actual, long expected, String message) {
        Assertions.assertEquals(expected, actual, message);
    }

    public static void assertEquals(long actual, long expected) {
        Assertions.assertEquals(expected, actual);
    }
    public static void assertEquals(double actual, double expected, double delta,  String message) {
        Assertions.assertEquals(expected, actual, delta, message);
    }

    public static void assertEquals(double actual, double expected, double delta) {
        Assertions.assertEquals(expected, actual, delta);
    }
    
    public static void assertEquals(float actual, float expected, String message) {
        Assertions.assertEquals(expected, actual, message);
    }

    public static void assertEquals(float actual, float expected) {
        Assertions.assertEquals(expected, actual);
    }
    
    public static void assertEquals(String actual, String expected, String message) {
        Assertions.assertEquals(expected, actual, message);
    }

    public static void assertEquals(String actual, String expected) {
        Assertions.assertEquals(expected, actual);
    }
    
    public static void assertEquals(char actual, char expected, String message) {
        Assertions.assertEquals(expected, actual, message);
    }

    public static void assertEquals(char actual, char expected) {
        Assertions.assertEquals(expected, actual);
    }
    
    public static void assertEquals(byte actual, byte expected, String message) {
        Assertions.assertEquals(expected, actual, message);
    }

    public static void assertEquals(byte actual, byte expected) {
        Assertions.assertEquals(expected, actual);
    }
    
    public static void assertEquals(Object actual, Object expected, String message) {
        Assertions.assertEquals(expected, actual, message);
    }

    public static void assertEquals(Object actual, Object expected) {
        Assertions.assertEquals(expected, actual);
    }
}