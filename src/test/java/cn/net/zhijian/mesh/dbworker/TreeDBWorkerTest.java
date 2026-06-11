package cn.net.zhijian.mesh.dbworker;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.dbworker.SqliteWorker.SqliteBuilder;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.util.ValParser;

public class TreeDBWorkerTest extends UnitTestBase {
    private static TreeDBWorker db;
    
    @BeforeAll
    public static void init() {
        String dbName = "tDb";
        String serviceName = "testsrv";
        SqliteBuilder builder = new SqliteBuilder(testService, 40, serviceName, dbName, 1, 5, AbsPlatform.dbRoot());
        builder.createIfAbsent(true);
        builder.longToStr(true);
        builder.backup(false);
        builder.slaves(null);
        db = TreeDBWorker.instance(0, builder);
    }
    
    @AfterAll
    public static void destroy() {
        try {
            db.close();
            if(db.rdb instanceof SqliteWorker) {
                String fileName = ((SqliteWorker)db.rdb).dbFile;
                FileUtil.remove(new File(fileName));
            }
        } catch (Exception e) {
            fail("Fail to close:" + e.getLocalizedMessage());
        }
    }
    
    @Test
    public void testTreeDBFeatures() {
        long ut = System.currentTimeMillis();

        TreeDBWorker.DBResultCode rt = db.createDir("/service", ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.OK);
        rt = db.createDir("/service/webdb", ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.OK);
        rt = db.put("/service/webdb/name", "sysdb", ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.OK);

        rt = db.putIfAbsent("/service/webdb/name", "sysdb", ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.ALREADY_EXISTS);
        ItemInfo ii = db.get("/service/webdb/name");
        assertTrue(ii != null
                && ii.name.equals("name")
                && ii.val.equals("sysdb")
        );

        rt = db.putList("/service/webdb/features", "A", ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.OK);
        rt = db.putList("/service/webdb/features", "B", ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.OK);

        ii = db.get("/service/webdb/features");
        assertTrue(ii != null && ii.val.equals("[\"A\",\"B\"]"));

        Map<String, Object> m = new HashMap<>();
        m.put("A", 1);
        m.put("B", "2");
        rt = db.putMap("/service/webdb/prop", m, ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.OK);

        m.clear();
        m.put("C","3");
        rt = db.putMap("/service/webdb/prop", m, ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.OK);

        m = db.getMap("/service/webdb/prop");
        assertTrue(m != null && m.size() == 3);
        String b = ValParser.getAsStr(m, "B");
        assertTrue(b.equals("2"));

        rt = db.putIfAbsent("/service/webdb/type", "rdb", ut);
        assertTrue(rt.equals(TreeDBWorker.DBResultCode.OK) || rt.equals(TreeDBWorker.DBResultCode.ALREADY_EXISTS));
        List<ItemInfo> iis = db.gets("/service/webdb");
        assertTrue(iis != null && iis.size() == 4);

        ut = System.currentTimeMillis();
        rt = db.remove("/service/webdb/name", ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.OK);
        rt = db.remove("/service/webdb/name", ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.OK);
        rt = db.removeDir("/service/webdb", ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.NOT_EMPTY);
        rt = db.removes("/service/webdb", ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.OK);

        rt = db.removeDir("/service/webdb", ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.OK);
        rt = db.removeDir("/service", ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.OK);
    }

    @Test
    public void testPutAtomic() {
        String atomicItem = "/atomic/a";
        long ut = System.currentTimeMillis();

        TreeDBWorker.DBResultCode rt = db.createDir("/atomic", ut);
        assertEquals(rt, TreeDBWorker.DBResultCode.OK);
        ItemInfo ii = db.putAtomic(atomicItem, "0", "0", ut);
        assertTrue(ii != null && ii.val.equals("0"));
        ii = db.putAtomic(atomicItem, "1", "0", ut);
        assertTrue(ii != null);
        assertTrue(ii.val.equals("1"));
        ii = db.putAtomic(atomicItem, "0", "2", ut);
        assertTrue(ii != null && ii.val.equals("1"));
    }
}
