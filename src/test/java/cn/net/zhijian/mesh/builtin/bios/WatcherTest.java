package cn.net.zhijian.mesh.builtin.bios;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.builtin.bios.Watcher.DBCfg;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.mesh.frm.intf.IDBConst.DBType;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

public class WatcherTest extends UnitTestBase {
    private static final String correctCfg = "{"
        + "    \"192.168.1.6:8523\":["
        + "        {"
        + "            \"no\":0,"
        + "            \"level\":0,"
        + "            \"shardStart\":0,"
        + "            \"shardEnd\":32768,"
        + "            \"type\":\"SQLITE\""
        + "        },"
        + "        {"
        + "            \"no\":1,"
        + "            \"level\":0,"
        + "            \"shardStart\":0,"
        + "            \"shardEnd\":16384,"
        + "            \"type\":\"SQLITE\""
        + "        },"
        + "        {"
        + "            \"no\":1,"
        + "            \"level\":1,"
        + "            \"shardStart\":16384,"
        + "            \"shardEnd\":32768,"
        + "            \"type\":\"SQLITE\","
        + "            \"master\":\"192.168.1.7:8523\""
        + "        },"        
        + "        {"
        + "            \"no\":3," //无备份
        + "            \"level\":0,"
        + "            \"shardStart\":0,"
        + "            \"shardEnd\":32768,"
        + "            \"type\":\"SQLITE\""
        + "        }"
        + "    ],"
        + "    \"192.168.1.7:8523\":["
        + "        {"
        + "            \"no\":0,"
        + "            \"level\":1,"
        + "            \"shardStart\":0,"
        + "            \"shardEnd\":32768,"
        + "            \"type\":\"SQLITE\","
        + "            \"master\":\"192.168.1.6:8523\""
        + "        },"
        + "        {"
        + "            \"no\":1,"
        + "            \"level\":0,"
        + "            \"shardStart\":16384,"
        + "            \"shardEnd\":32768,"
        + "            \"type\":\"SQLITE\""
        + "        },"
        + "        {"
        + "            \"no\":1,"
        + "            \"level\":1,"
        + "            \"shardStart\":0,"
        + "            \"shardEnd\":16384,"
        + "            \"type\":\"SQLITE\","
        + "            \"master\":\"192.168.1.6:8523\""
        + "        }"
        + "    ],"
        + "    \"192.168.1.8:8523\":[" //192.168.1.6:8523有两个备份
        + "        {"
        + "            \"no\":0,"
        + "            \"level\":1,"
        + "            \"shardStart\":0,"
        + "            \"shardEnd\":32768,"
        + "            \"type\":\"SQLITE\","
        + "            \"master\":\"192.168.1.6:8523\""
        + "        }"
        + "    ]"
        + "}";
    
    private static final String incorrectCfg = "{"
            + "    \"192.168.1.6:8523\":["
            + "        {"
            + "            \"no\":0,"
            + "            \"level\":0,"
            + "            \"shardStart\":0,"
            + "            \"shardEnd\":32768,"
            + "            \"type\":\"SQLITE\""
            + "        },"
            + "        {"
            + "            \"no\":1,"
            + "            \"level\":0,"
            + "            \"shardStart\":0,"
            + "            \"shardEnd\":16383,"
            + "            \"type\":\"SQLITE\""
            + "        },"
            + "        {"
            + "            \"no\":1,"
            + "            \"level\":1,"
            + "            \"shardStart\":16384,"
            + "            \"shardEnd\":32768,"
            + "            \"type\":\"SQLITE\","
            + "            \"master\":\"192.168.1.7:8523\""
            + "        }"
            + "    ],"
            + "    \"192.168.1.7:8523\":["
            + "        {"
            + "            \"no\":0,"
            + "            \"level\":1,"
            + "            \"shardStart\":0,"
            + "            \"shardEnd\":32768,"
            + "            \"type\":\"SQLITE\","
            + "            \"master\":\"192.168.1.6:8523\""
            + "        },"
            + "        {"
            + "            \"no\":1,"
            + "            \"level\":0,"
            + "            \"shardStart\":16384,"
            + "            \"shardEnd\":32768,"
            + "            \"type\":\"SQLITE\""
            + "        },"
            + "        {"
            + "            \"no\":1,"
            + "            \"level\":1,"
            + "            \"shardStart\":0,"
            + "            \"shardEnd\":16383,"
            + "            \"type\":\"SQLITE\","
            + "            \"master\":\"192.168.1.6:8523\""
            + "        }"
            + "    ]"
            + "}";
    @Test
    public void testWebdbConfigParse() {
        Map<String, Object> dbs = JsonUtil.jsonToMap(correctCfg, true);
        if(dbs == null || dbs.isEmpty()) {
            fail("invalid webdb config");
            return;
        }

        try {
            List<DBCfg> cfgs = Watcher.parseWebdbCfg(dbs);
            for(DBCfg cfg : cfgs) {
                assertTrue(cfg.level >= 0);
                assertTrue(cfg.readConn > 0);
                if(cfg.type.equals(DBType.SQLITE.name())) {
                    assertEquals(cfg.writeConn, 1);
                } else {
                    assertTrue(cfg.writeConn > 0);
                }
                Map<String, Object> m = JsonUtil.jsonToMap(cfg.toString());
                assertTrue(m != null && m.size() > 0);
            }
            
            Map<String, StringBuilder> jsons = DBCfg.toJsons(cfgs); //addr -> cfgjson
            for(Map.Entry<String, StringBuilder> j : jsons.entrySet()) {
                String addr = j.getKey();
                List<Object> l = JsonUtil.jsonToList(j.getValue().toString());
                assertTrue(l != null && l.size() > 0);
                for(Object o : l) {
                    Map<String, Object> cfg = ValParser.parseObject(o);
                    System.out.println(cfg);
                    String type = ValParser.getAsStr(cfg, IDBConst.CFG_SEG_TYPE);
                    assertEquals(type, DBType.SQLITE.name());
                    int writeConn = ValParser.getAsInt(cfg, IDBConst.CFG_SEG_WRITECONN);
                    assertEquals(writeConn, 1);
                    String s = ValParser.getAsStr(cfg, IDBConst.CFG_SEG_SLAVES);
                    if(!StringUtil.isEmpty(s)) {
                        String[] slaves = s.split(",");
                        for(String slave : slaves) {
                            assertTrue(!slave.equals(addr));
                            int start = ValParser.getAsInt(cfg, IDBConst.CFG_SEG_SHARDSTART);
                            int end = ValParser.getAsInt(cfg, IDBConst.CFG_SEG_SHARDEND);
                            int no = ValParser.getAsInt(cfg, IDBConst.CFG_SEG_NO);
                            DBCfg c = Watcher.DBCfg.find(cfgs, slave, no, start, end);
                            assertNotNull(c);
                            assertTrue(c.addr.equals(slave));
                        }
                    }
                }
            }
        } catch(MeshException e) {
            fail("Invalid webdb config,result:" + e.getMessage());
        }
    }
    
    @Test
    public void testWrongWebdbConfig() {
        String addr = "192.168.0.1:8523";
        Map<String, Object> oneNode = MapBuilder.of("no", 0, "level", 1, "shardStart", 0, "shardEnd", 32768);
        oneNode.put(IDBConst.CFG_SEG_MASTER, "192.168.0.7:8523");
        try {
            DBCfg cfg = Watcher.DBCfg.parse(addr, oneNode);
            assertNotNull(cfg);
        } catch(MeshException e) {
            fail("Invalid webdb config,result:" + e.getMessage());
        }

        oneNode.put(IDBConst.CFG_SEG_SHARDSTART, "-1");
        try {
            Watcher.DBCfg.parse(addr, oneNode);
            fail("Invalid webdb config, fail to judge " + IDBConst.CFG_SEG_SHARDSTART);
        } catch(MeshException e) {
            System.out.println("testWrongWebdbConfig:" + e.getMessage());
        }
        
        oneNode.put(IDBConst.CFG_SEG_SHARDSTART, "100");
        oneNode.put(IDBConst.CFG_SEG_SHARDEND, "100");
        try {
            Watcher.DBCfg.parse(addr, oneNode);
            fail("Invalid webdb config, fail to judge " + IDBConst.CFG_SEG_SHARDSTART + " and " + IDBConst.CFG_SEG_SHARDEND);
        } catch(MeshException e) {
            System.out.println("testWrongWebdbConfig:" + e.getMessage());
        }
        
        oneNode.put(IDBConst.CFG_SEG_MASTER, "181.168.11.1:8523");
        try {
            Watcher.DBCfg.parse(addr, oneNode);
            fail("Invalid webdb config,fail to judge " + IDBConst.CFG_SEG_MASTER);
        } catch(MeshException e) {
            System.out.println("testWrongWebdbConfig:" + e.getMessage());
        }
    }
    
    @Test
    public void testWebdbConfigWrongNo() {
        String addr = "192.168.0.1:8523";
        Map<String, Object> oneNode = MapBuilder.of("no", -1, "level", 1, "shardStart", 0, "shardEnd", 32768);
        try {
            Watcher.DBCfg.parse(addr, oneNode);
            fail("Invalid webdb config, fail to judge " + IDBConst.CFG_SEG_NO);
        } catch(MeshException e) {
            System.out.println("testWebdbConfigWrongNo:" + e.getMessage());
        }

        oneNode.put(IDBConst.CFG_SEG_NO, 1100000000);
        try {
            Watcher.DBCfg.parse(addr, oneNode);
            fail("Invalid webdb config, fail to judge " + IDBConst.CFG_SEG_NO);
        } catch(MeshException e) {
            System.out.println("testWebdbConfigWrongNo:" + e.getMessage());
        }
    }
    
    @Test
    public void testShardingsCheck() {
        Map<String, Object> dbs = JsonUtil.jsonToMap(incorrectCfg, true);
        if(dbs == null || dbs.isEmpty()) {
            fail("invalid webdb config");
            return;
        }

        try {
            Watcher.parseWebdbCfg(dbs);
            fail("Invalid webdb config,fail to check shardings:");
        } catch(MeshException e) {
            System.out.println("testShardingsCheck:" + e.getMessage());
        }
    }
}
