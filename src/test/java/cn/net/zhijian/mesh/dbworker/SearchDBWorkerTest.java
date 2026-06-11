package cn.net.zhijian.mesh.dbworker;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Set;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.StringSpliter;

public class SearchDBWorkerTest extends UnitTestBase {
    static {
        try {
            StringSpliter.init(FileUtil.addPath(getUserDir(), IConst.SYS_CONF_DIR, "dictionary.txt"), 3);
        } catch(Exception e) {
            fail("Fail to init spliter" + e);
        }
    }

    @Test
    public void testSpliter() {
        String s = "你好，我是分词工具";
        Set<String> words = StringSpliter.getKeyWords(s);
        System.out.println("words:" + words);
        assertTrue(words.contains("工具"));
        assertTrue(words.contains("分词"));
        assertTrue(words.contains("你好"));

        s = "帘外雨潺潺，春意阑珊。\n"
                + "　　罗衾不耐五更寒。\n"
                + "　　梦里不知身是客，一晌贪欢。\n"
                + "　　独自莫凭栏，无限江山。\n"
                + "　　别时容易见时难。"
                + "   流水落花春去也，天上人间";
        words = StringSpliter.getKeyWords(s);
        System.out.println("words:" + words);
        assertTrue(words.contains("春意"));
        assertTrue(words.contains("梦里"));
        assertTrue(words.contains("五更"));
        assertTrue(words.contains("流水"));
        assertTrue(words.contains("落花"));
        assertTrue(words.contains("天上"));
        assertTrue(words.contains("人间"));
    }
    
    @Test
    public void testSearchDBWorker() {
        boolean res;
        List<Object> docs;
        String dbName = "sDb";
        String fileName = null;
        //ServiceInfo si, String service, String dbName, boolean createIfNotExists, String[] slaves
        SearchDBWorker db = (SearchDBWorker)SearchDBWorker.localInstance(testService, dbName, null);
        assertNotNull(db);
        try {
            //StringSpliter.init(FileUtil.addPath(getUserDir(), "services", "webdb", "dictionary.txt"), 3);
            res = db.putDocument("tab1", "1", "A B don't 好", "test,爸爸妈妈姐姐弟弟", "返回 File，获取外部存储目录");
            assertTrue(res);
            res = db.putDocument("tab1", "2", "文件", "获取文件路径", "context 方式获取的路径都是跟该app相关的某个路径");
            assertTrue(res);
            res = db.putDocument("tab1", "3", "路径", "缓存路径", "缓存路径");
            assertTrue(res);
            
            //test search
            docs = db.getDocuments("tab1", 3, new String[]{"爸爸"});
            assertTrue(docs != null && docs.size() == 1 && docs.get(0).equals("1"));
            docs = db.getDocuments("tab1", 3, new String[]{"妈妈"});
            assertTrue(docs != null && docs.size() == 1);
            docs = db.getDocuments("tab1", 3, new String[]{"爸爸 姐姐", "好"});
            assertTrue(docs != null && docs.size() == 1);
            docs = db.getDocuments("tab1", 3, new String[]{"路径"});
            assertTrue(docs != null && docs.size() == 2);
            
            //test remove
            res = db.removeDocument("tab1", "3");
            assertTrue(res);
            docs = db.getDocuments("tab1", 3, new String[]{"缓存"});
            assertTrue(docs != null && docs.size() == 0);
            
            
            //test multi tables
            res = db.putDocument("tab2", "1", "A B don't", "流水落花春去也，天上人间", "李煜");
            assertTrue(res);
            docs = db.getDocuments("tab2", 3, new String[]{"人间"});
            assertTrue(docs != null && docs.size() == 1 && docs.get(0).equals("1"));
            
            docs = db.getDocuments("tab1", 3, new String[]{"人间"});
            assertTrue(docs != null && docs.size() == 0);
            
            if(db.getCoreDb() instanceof SqliteWorker) {
                fileName = ((SqliteWorker)db.getCoreDb()).dbFile;
            }
        } catch (Exception e) {
            fail("Fail to execute put sqls:" + e);
        } finally {
            if(fileName != null) {
                FileUtil.remove(new File(fileName));
            }
            db.close();
        }
    }
    
    @Test
    public void testLocSearchDBWorker() {
        String dbName = "locDb";
        boolean res;
        List<Object> docs;
        //ServiceInfo si, String service, String dbName, boolean createIfNotExists
        String tab = SearchDBWorker.UNDEFINED_TABLE;
        String fileName = null;
        SearchDBWorker db = (SearchDBWorker)SearchDBWorker.localInstance(testService, dbName, null);
        assertNotNull(db);
        try {
            res = db.putDocument(tab, "1", "A B don't 好", "test,爸爸妈妈姐姐弟弟", "返回 File，获取外部存储目录");
            assertTrue(res);
            res = db.putDocument(tab, "2", "文件", "获取文件路径", "context 方式获取的路径都是跟该app相关的某个路径");
            assertTrue(res);
            res = db.putDocument(tab, "3", "路径", "缓存路径", "缓存路径");
            assertTrue(res);
            try {
                docs = db.getDocuments(tab, 3, new String[]{"爸爸"});
                assertTrue(docs != null && docs.size() == 1 && docs.get(0).equals("1"));
                docs = db.getDocuments(tab, 3, new String[]{"妈妈"});
                assertTrue(docs != null && docs.size() == 1);
                docs = db.getDocuments(tab, 3, new String[]{"爸爸 姐姐", "好"});
                assertTrue(docs != null && docs.size() == 1);
                docs = db.getDocuments(tab, 3, new String[]{"路径"});
                assertTrue(docs != null && docs.size() == 2);
            } catch (Exception e) {
                fail("Fail to execute get sqls:" + e.getLocalizedMessage());
            }
            
            //test remove
            try {
                res = db.removeDocument(tab, "1");
                assertTrue(res);
                docs = db.getDocuments(tab, 3, new String[]{"爸爸"});
                assertTrue(docs != null && docs.size() == 0);
            } catch (Exception e) {
                fail("Fail to execute get sqls:" + e.getLocalizedMessage());
            }

            if(db.getCoreDb() instanceof SqliteWorker) {
                fileName = ((SqliteWorker)db.getCoreDb()).dbFile;
            }
        } catch (Exception e) {
            fail("Fail to execute put sqls:" + e);
        } finally {
            if(fileName != null) {
                FileUtil.remove(new File(fileName));
            }
            db.close();
        }
    }
}
