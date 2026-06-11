package cn.net.zhijian.mesh.builtin.webdb;

import java.util.List;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.frm.intf.IDBConst.DBType;
import cn.net.zhijian.util.JsonUtil;

/**
 * 
 * @author flyinmind of csdn.net
 *
 */
public class WebdbRunConfigTest extends UnitTestBase {
    private static final String sqliteCfgWithoutSlaves = "[\n"
            + "        {\n"
            + "            \"type\":\"SQLITE\",\n"
            + "            \"level\":0,\n"
            + "            \"no\":0,\n"
            + "            \"shardStart\":0,\n"
            + "            \"shardEnd\":100\n"
            + "        }\n"
            + "]";
    private static final String sqliteCfgWithSlaves = "[\n"
            + "        {\n"
            + "            \"type\":\"SQLITE\",\n"
            + "            \"level\":0,\n"
            + "            \"no\":0,\n"
            + "            \"shardStart\":0,\n"
            + "            \"shardEnd\":32768,\n"
            + "            \"slaves\":\"192.168.1.4:8080\""
            + "        },\n"
            + "        {\n"
            + "            \"type\":\"SQLITE\",\n"
            + "            \"level\":0,\n"
            + "            \"no\":0,\n"
            + "            \"shardStart\":100,\n"
            + "            \"shardEnd\":32768,"
            + "            \"slaves\":\"192.168.1.4:8080\""
            + "        },\n"
            + "        {\n"
            + "            \"type\":\"SQLITE\",\n"
            + "            \"level\":1,\n"
            + "            \"no\":1,\n"
            + "            \"shardStart\":100,\n"
            + "            \"shardEnd\":32768,\n"
            + "            \"slaves\":\"192.168.1.4:8080\""
            + "        }\n"
            + "]";
    
    private static final String mysqlCfgWithSlaves = "[\n"
            + "        {\n"
            + "            \"type\":\"MySQL\",\n"
            + "            \"level\":0,\n"
            + "            \"no\":0,\n"
            + "            \"shardStart\":0,\n"
            + "            \"shardEnd\":32768,\n"
            + "            \"slaves\":\"192.168.1.4:8080\""
            + "        },\n"
            + "        {\n"
            + "            \"type\":\"MySQL\",\n"
            + "            \"level\":0,\n"
            + "            \"no\":0,\n"
            + "            \"shardStart\":100,\n"
            + "            \"shardEnd\":32768,\n"
            + "            \"slaves\":\"192.168.1.4:8080\""
            + "        },\n"
            + "        {\n"
            + "            \"type\":\"MySQL\",\n"
            + "            \"level\":1,\n"
            + "            \"no\":1,\n"
            + "            \"shardStart\":100,\n"
            + "            \"shardEnd\":32768,\n"
            + "            \"master\":\"192.168.1.4:8080\"\n"
            + "        }\n"
            + "]";
    
    @Test
    public void testSqliteRunConfigWithSlaves() {
        List<Object> cfgList = JsonUtil.jsonToList(sqliteCfgWithSlaves);
        RunConfig rc = RunConfig.parse(cfgList);
        assertTrue(rc.dbs.length == 3);
        assertEquals(rc.dbs[0].type(), DBType.SQLITE);
        assertTrue(rc.dbs[2].no == 1);
        assertTrue(rc.dbs[0].slaves.length == 1);
        assertTrue(rc.maxLevel() == 1);
    }
    
    @Test
    public void testSqliteRunConfigWithoutSlaves() {
        List<Object> cfgList = JsonUtil.jsonToList(sqliteCfgWithoutSlaves);
        RunConfig rc = RunConfig.parse(cfgList);
        assertNotNull(rc);
        assertTrue(rc.dbs.length == 1);
        assertNull(rc.dbs[0].slaves);
    }
    
    @Test
    public void testMysqlRunConfigWithSlaves() {
        List<Object> cfgList = JsonUtil.jsonToList(mysqlCfgWithSlaves);
        RunConfig rc = RunConfig.parse(cfgList);
        assertEquals(rc.dbs[0].type(), DBType.MYSQL);
        assertTrue(rc.dbs.length == 3);
        assertTrue(rc.dbs[2].no == 1);
        assertTrue(rc.dbs[0].slaves.length == 1);
        assertTrue(rc.maxLevel() == 1);
    }
}
