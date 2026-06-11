package cn.net.zhijian.mesh.dbworker;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.fileq.FQException;
import cn.net.zhijian.fileq.intf.IMessage;
import cn.net.zhijian.fileq.intf.IMessageHandler;
import cn.net.zhijian.fileq.intf.IReader;
import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.dbworker.SqliteWorker.SqliteBuilder;
import cn.net.zhijian.mesh.frm.abs.AbsConnection;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.IUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

public class SqliteWorkerTest extends UnitTestBase {
    private static final String SERVICE = "testsrv";
    private static final String INITVER = "0.1.0";
    private static final String dbName = "rDb";

    private void executeWrite(SqliteWorker db, String sql, String now,
            Map<String, Integer> results, CountDownLatch counter) {
        new Thread() {
            @Override
            public void run() {
                try(AbsConnection writeConn = db.getWriteConn()) {
                    String s = AbsRDBWorker.modifyDMLSql(sql, now);
                    int result = db.executeRawDML(writeConn, s);
                    results.put(sql, result);
                } catch (Exception e) {
                    fail("Fail to executeRawDML", e);
                } finally {
                    counter.countDown();
                }
            }
        }.start();
    }
    
    private void executeRead(Runnable run) {
        new Thread(run).start();
    }
    
    private void testSqliteWorkerFeatures(SqliteBuilder builder) throws InterruptedException {
        SqliteWorker db = builder.build(0);

        assertNotNull(db);
        assertTrue(db.normal());
        List<Object> initSqls = JsonUtil.jsonToListWithLF("[{\n"
            + "\"minVer\":\"0.0.0\",\"maxVer\":\"0.1.0\",\n"
            + "\"sqls\":[\n"
            + "\"create table if not exists config ( -- 公司级配置\n"
            + "    k         varchar(255) not null primary key, -- 配置项名称\n"
            + "    v         text not null  -- 配置内容，不限格式\n"
            + ")\""
            + "]}]");
        assertNotNull(initSqls);

        String now = "" + System.currentTimeMillis();
        if(!db.execInitDDLs(initSqls, INITVER)) {
            fail("Fail to init tables");
        }
        
        String a1 = "" + System.currentTimeMillis();
        String a2 = "" + System.currentTimeMillis();
        String a3 = "" + (System.currentTimeMillis()+1);

        try(AbsConnection writeConn = db.getWriteConn()) {
            db.executeRawDML(writeConn, "delete from config");
            String[] sqlList = new String[] {
                "replace into config(k,v) values('A','"+a1+"')",
                "replace into config(k,v) values('B','"+a2+"')",
                "replace into config(k,v) values('A','"+a3+"')"
            };
            for(String sql : sqlList) {
                String s = AbsRDBWorker.modifyDMLSql(sql, now);
                int result = db.executeRawDML(writeConn, s);
                System.out.println(s);
                assertEquals(1, result);
            }
        } catch (Exception e) {
            fail("Fail to execute insert sqls:" + e.getLocalizedMessage());
        }

        CountDownLatch counter = new CountDownLatch(4);
        executeRead(() -> {
            try {
                List<Map<String, Object>> maps = db.queryMaps("select k,v from config order by k");
                assertTrue(maps != null && maps.size() == 2);
                String v = ValParser.getAsStr(maps.get(1), "v");
                assertEquals(v, a2);
            } catch (SQLException | MeshException e) {
                fail("Fail to execute sqls:" + e.getLocalizedMessage());
            } finally {
                counter.countDown();
            }
        });
        
        executeRead(() -> {
            try {
                List<Object> res = db.querySingleList("select k from config");
                assertTrue(res != null && res.size() == 2); //2行
                Object[] line = db.queryLine("select k,v from config");
                assertTrue(line != null && line.length == 2); //2列
            } catch (Exception e) {
                fail("Fail to execute sqls:" + e.getLocalizedMessage());
            } finally {
                counter.countDown();
            }
        });

        executeRead(() -> {
            try {
                Map<String, Object> map = db.queryMap("select k,v from config where k='C'");
                assertTrue(map == null || map.size() <= 0);
            } catch (SQLException | MeshException e) {
                fail("Fail to execute sqls:" + e.getLocalizedMessage());
            } finally {
                counter.countDown();
            }
        });
        
        executeRead(() -> {
            try {
                Map<String, Object> map = db.queryMap("select max(v) from config where k='C' group by k");
                assertTrue(map == null || map.size() <= 0);
            } catch (SQLException | MeshException e) {
                fail("Fail to execute sqls:" + e.getLocalizedMessage());
            } finally {
                counter.countDown();
            }
        });
        counter.await();

        String[] sqls = new String[] {
            "insert into config(k,v) values('C','V1'),('D','V2')",
            "update config set v='1' where k='E'",
            "update config set v='1' where k in('A','B')",
            "delete from config"
        };
        CountDownLatch counter1 = new CountDownLatch(sqls.length);
        Map<String, Integer> results = new ConcurrentHashMap<>();
        for(String sql : sqls) {
            executeWrite(db, sql, now, results, counter1);
        }
        counter1.await();
        Integer result = results.get(sqls[0]);
        assertEquals(result.intValue(), 2, sqls[0]);
        result = results.get(sqls[1]);
        assertEquals(result.intValue(), 0, sqls[1]);
        result = results.get(sqls[2]);
        assertEquals(result.intValue(), 2, sqls[2]);
        result = results.get(sqls[3]);
        assertEquals(result.intValue(), 4, sqls[3]);

        String dbFileName = db.dbFile;
        try {
            db.close();
        } catch(Exception e) {
            fail("Fail to close db" + e);
        }
        FileUtil.remove(new File(dbFileName));
    }
    
    @Test
    public void testSqliteWorkerBaseFeatures() throws InterruptedException {
        SqliteBuilder builder = new SqliteBuilder(testService, 40, SERVICE, dbName, 1, 5, AbsPlatform.dbRoot());
        builder.createIfAbsent(true);
        builder.longToStr(true);
        builder.backup(false);
        builder.slaves(null);
        testSqliteWorkerFeatures(builder);
    }
    
    @Test
    public void testSqliteWorkerWith1ReadConn() throws InterruptedException {
        SqliteBuilder builder = new SqliteBuilder(testService, 40, SERVICE, dbName, 1, 1, AbsPlatform.dbRoot());
        builder.createIfAbsent(true);
        builder.longToStr(true);
        builder.backup(false);
        builder.slaves(null);
        testSqliteWorkerFeatures(builder);
    }
    
    @Test
    public void testSqliteWorkerSubsOf() {
        //boolean createIfNotExists, boolean longToStr, String[] slaves
        SqliteBuilder builder = new SqliteBuilder(testService, 40, SERVICE, dbName, 1, 5, AbsPlatform.dbRoot());
        builder.createIfAbsent(true);
        builder.longToStr(true);
        builder.backup(false);
        builder.slaves(null);
        
        SqliteWorker db = builder.build(0);

        assertNotNull(db);
        assertTrue(db.normal());
        List<Object> initSqls = JsonUtil.jsonToListWithLF("[{\n"
            + "\"minVer\":\"0.0.0\",\"maxVer\":\"0.1.0\",\n"
            + "\"sqls\":[\n"
            + "\"create table if not exists tree ( -- 公司级配置\n"
            + "    id        int not null primary key,\n"
            + "    fid       int not null, -- 父ID\n"
            + "    v         text not null\n"
            + ")\","
            + "\"insert into tree(id,fid,v) values"
            + "(1,0,'test1'),"
            + "(2,1,'test1'),"
            + "(3,2,'test1'),"
            + "(4,2,'test1'),"
            + "(5,1,'test1'),"
            + "(6,1,'test1')\""
            + "]}]");
        assertNotNull(initSqls);

        if(!db.execInitDDLs(initSqls, INITVER)) {
            fail("Fail to init tables");
        }

        try {
            List<Object> res = db.querySingleList("select subsof(1) from tree");
            assertTrue(res != null && res.size() == 5);
            res = db.querySingleList("select subsof(2) from tree");
            assertTrue(res != null && res.size() == 2);
            res = db.querySingleList("select subsof(1,id, fid) from tree");
            assertTrue(res != null && res.size() == 5);
            res = db.querySingleList("select subsof(2,id,fid) from tree");
            assertTrue(res != null && res.size() == 2);
            res = db.querySingleList("select subsof(2,id,fid,2) from tree");
            assertTrue(res != null && res.size() == 3);
            int id0 = ValParser.parseInt(res.get(0), -1);
            int id1 = ValParser.parseInt(res.get(1), -1);
            assertEquals(id0, 2);
            assertEquals(id1, 3);
        } catch (Exception e) {
            fail("Fail to execute sqls:" + e.getLocalizedMessage());
        }

        String dbFileName = db.dbFile;
        try {
            db.close();
        } catch(Exception e) {
            fail("Fail to close db" + e);
        }
        FileUtil.remove(new File(dbFileName));
    }
    
    @Test
    public void testSqliteAddColIfNotExists() {
        //boolean createIfNotExists, boolean longToStr, String[] slaves
        SqliteBuilder builder = new SqliteBuilder(testService, 40, SERVICE, dbName, 1, 5, AbsPlatform.dbRoot());
        builder.createIfAbsent(true);
        builder.longToStr(true);
        builder.backup(false);
        builder.slaves(null);
        
        SqliteWorker db = builder.build(0);
        assertNotNull(db);
        assertTrue(db.normal());
        List<Object> initSqls = JsonUtil.jsonToListWithLF("[{\n"
            + "\"minVer\":\"0.0.0\",\"maxVer\":\"0.1.0\",\n"
            + "\"sqls\":[\n"
            + "\"create table if not exists config ( -- 公司级配置\n"
            + "    k         varchar(255) not null primary key, -- 配置项名称\n"
            + "    v         text not null  -- 配置内容，不限格式\n"
            + ")\","
            + "\"alter table config add column if not exists test int not null default 0\""
            + "]}]");
        assertNotNull(initSqls);

        String now = "" + System.currentTimeMillis();
        if(!db.execInitDDLs(initSqls, INITVER)) {
            fail("Fail to init tables");
        }
        
        String a1 = "" + System.currentTimeMillis();
        String a2 = "" + System.currentTimeMillis();
        String a3 = "" + (System.currentTimeMillis()+1);
        int result;
        String[] sqlList = new String[] {
            "replace into config(k,v,test) values('A','"+a1+"',0)",
            "replace into config(k,v,test) values('B','"+a2+"',0)",
            "replace into config(k,v,test) values('A','"+a3+"',0)"
        };

        try(AbsConnection writeConn = db.getWriteConn()) {
            db.executeRawDML(writeConn, "delete from config");
            for(String sql : sqlList) {
                String s = AbsRDBWorker.modifyDMLSql(sql, now);
                result = db.executeRawDML(writeConn, s);
                System.out.println(s);
                assertEquals(1, result);
            }
        } catch (Exception e) {
            fail("Fail to execute insert sqls:" + e.getLocalizedMessage());
        }
        initSqls = JsonUtil.jsonToListWithLF("[{\n"
                + "\"minVer\":\"0.0.0\",\"maxVer\":\"0.1.0\",\n"
                + "\"sqls\":[\n"
                + "\"create table if not exists config ( -- 公司级配置\n"
                + "    k         varchar(255) not null primary key, -- 配置项名称\n"
                + "    v         text not null  -- 配置内容，不限格式\n"
                + ")\","
                + "\"alter table\tconfig add column\tif not exists test\tint not null default 0\""
                + "]}]");
        assertNotNull(initSqls);
        if(!db.execInitDDLs(initSqls, INITVER)) {
            fail("Fail to init tables");
        }
        
        String dbFileName = db.dbFile;
        try {
            db.close();
        } catch(Exception e) {
            fail("Fail to close db" + e);
        }
        FileUtil.remove(new File(dbFileName));
    }
    
    @Test
    public void testSqliteWorkerBackup() throws InterruptedException {
        String dbName = "rDb1";

        //boolean createIfNotExists, boolean longToStr, String[] slaves
        SqliteBuilder builder = new SqliteBuilder(testService, 40, SERVICE, dbName, 1, 5, AbsPlatform.dbRoot());
        builder.createIfAbsent(true);
        builder.longToStr(true);
        builder.backup(true);
        builder.slaves(null);

        CountDownLatch counter = new CountDownLatch(5); //三次系统表操作，一次建表，一次批量写操作
        SqliteWorker db = builder.build(0);
        try {
            db.addConsumer(dbName + "_test", new IMessageHandler() {
                private final AtomicInteger n = new AtomicInteger(0);
                /**
                 * msg是一个sqls字符串
                 * 字符串的头部有service@dbName@....
                 */
                @Override
                public boolean handle(IMessage msg, IReader reader) {
                    String s = new String(msg.message(), 0, msg.len(), IUtil.DEFAULT_CHARSET);
                    String[] sqls = StringUtil.split(s, ';', '\'', true);
                    for(String sql : sqls) {
                        System.out.println("sync " + n.get() + ":" + sql);
                    }
                    n.incrementAndGet();
                    reader.confirm(true);
                    counter.countDown();
                    return true;
                }
            });
        } catch (FQException e) {
            fail("Fail to add consumer", e);
        }
        assertNotNull(db);
        assertTrue(db.normal());
        String createTabSql = "create table if not exists config ( -- 公司级配置\n"
                + "k         varchar(255) not null primary key, -- 配置项名称\n"
                + "v         text not null  -- 配置内容，不限格式\n"
                + ")";
        List<Object> initSqls = JsonUtil.jsonToListWithLF("[{\n"
                + "\"minVer\":\"0.0.0\",\"maxVer\":\"0.1.0\",\n"
                + "\"sqls\":[\n\"" + createTabSql + "\"]}]");
        assertNotNull(initSqls);

        String now = "" + System.currentTimeMillis();
        if(!db.execInitDDLs(initSqls, INITVER)) {
            fail("Fail to init tables");
        }
        
        long cur = System.currentTimeMillis();
        String a1 = "" + (cur - 1);
        String a2 = "" + cur;
        String a3 = "" + (cur + 1);
        int result;

        String[] sqlArr = new String[] {
            "replace into config(k,v) values('A','"+a1+"')",
            "replace into config(k,v) values('B','"+a2+"')",
            "replace into config(k,v) values('A','"+a3+"')"
        };
        List<String> sqls = new ArrayList<>(sqlArr.length);
        try(AbsConnection writeConn = db.getWriteConn()) {
            for(String sql : sqlArr) {
                String s = AbsRDBWorker.modifyDMLSql(sql, now);
                result = db.executeRawDML(writeConn, s);
                assertEquals(result, 1);
                sqls.add(s);
            }
            db.sync(sqls); //逐个写入，批量同步
        } catch (Exception e) {
            fail("Fail to execute insert sqls:" + e);
        }

        if(!counter.await(3000, TimeUnit.MILLISECONDS)) {
            fail("Synchronization used too much time");
        }
        Thread.sleep(500); //等从库写完
        String sql = "select k,v from config order by k";
        try(AbsConnection conn = db.getBackupConn()) { //在备份库中执行查询
            List<Map<String, Object>> maps = db.queryMaps(conn, sql);
            assertNotNull(maps);
            assertEquals(maps.size(), 2);
            String v = ValParser.getAsStr(maps.get(1), "v");
            assertEquals(v, a2);
            
            sql = "select max(v) from config where k='C' group by k";
            Map<String, Object> map = db.queryMap(conn, sql);
            assertTrue(map == null || map.size() <= 0);
        } catch (Exception e) {
            fail("Fail to execute sql `" + sql + "`", e);
        }

        try {
            db.close();
        } catch(Exception e) {
            fail("Fail to close db:" + e);
        }
        FileUtil.remove(new File(db.dbFile));
        FileUtil.remove(new File(db.bakDbFile));
    }
}
