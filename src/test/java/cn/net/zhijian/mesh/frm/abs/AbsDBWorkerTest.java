package cn.net.zhijian.mesh.frm.abs;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.prot.http.HttpServerRequest4Test;
import cn.net.zhijian.util.MapBuilder;

public class AbsDBWorkerTest extends UnitTestBase {
    @Test
    public void testCompileSql() {
        Map<String, String> headers = MapBuilder.of("cid", "40");
        Map<String, Object> params = MapBuilder.of("res", 1, "strPara", "don't");
        Map<String, Object> resp = MapBuilder.of("res", 1, "strPara", "don't");
        AbsServerRequest req = HttpServerRequest4Test.create(headers, params);
        String sql = "insert into t(a,b,c,d) values(@[^cid],'@[strPara]',@[!res],@{tt})";
        String sql1 = AbsRDBWorker.compileScript(sql, req, resp);
        assertEquals(sql1, "insert into t(a,b,c,d) values(40,'don''t',1,@{tt})");
        
        sql = "@[^cid],'@[strPara]',@[!res],@{!res},@{res}";
        sql1 = AbsRDBWorker.compileScript(sql, req, resp);
        assertEquals(sql1, "40,'don''t',1,@{!res},@{res}");
        
        sql = "@[^cid],'@[strPara]',@[!res],@[!res...";
        sql1 = AbsRDBWorker.compileScript(sql, req, resp);
        assertEquals(sql1, "40,'don''t',1,@[!res...");
    }
    
    @Test
    public void testCompileServerRs() {
        Map<String, String> headers = MapBuilder.of("cid", "40");
        Map<String, Object> params = MapBuilder.of("price", 1, "comment", "don't", "id", 1);
        Map<String, Object> resp = MapBuilder.of("price", 1, "comment", "don't");
        AbsServerRequest req = HttpServerRequest4Test.create(headers, params);
        String sql = "rs:\n"
                + "@[SWITCH|!status,'i.==',100, `{\"code\":5103,\"info\":\"order workflow is over\"}`,\n"
                + "|,!status,'i.==',0,`update orders set cmt='@{comment}',price=@{price} where id=@{id}`,\n"
                + "|,`update orders set cmt='@{comment}' where id=@{id}`]";
        String sql1 = AbsRDBWorker.compileScript(sql, req, resp);
        assertEquals(sql1, "rs:\nupdate orders set cmt='don''t',price=1 where id=1");
    }
    
    public void testValidTreeDBAct() {
        assertTrue(AbsDBWorker.isValidTreeDBAct("put"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("puts")); //写入多个键值对
        assertTrue(AbsDBWorker.isValidTreeDBAct("putIfAbsent")); //如果不存在，才put
        assertTrue(AbsDBWorker.isValidTreeDBAct("putList"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("putMap")); //在原有的map基础上更新

        assertTrue(AbsDBWorker.isValidTreeDBAct("rmv"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("rmvFromMap"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("rmvFromList"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("rmvs"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("rmvDir"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("crtDir"));

        assertTrue(AbsDBWorker.isValidTreeDBAct("get"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("update")); //用于search中，更新部分字段
        assertTrue(AbsDBWorker.isValidTreeDBAct("gets"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("getSubs"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("getSubsAndItems"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("getMap"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("getsMap"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("getId"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("list"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("itemExists"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("dirExists"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("names"));
        assertTrue(AbsDBWorker.isValidTreeDBAct("script"));
        
        assertFalse(AbsDBWorker.isValidTreeDBAct("1script"));
    }

    @Test
    public void testTranslateSql() {
        String sql = "\"insert into user(id,account,pwd,nickName)\n"
                + "values(@{ABSHASH|'admin'},'admin','@{PBKDF|6,'123456'}','企业主')\",\n"
                + "\"insert into grp(id,fid,name,decr) values(0,-1,'root','公司')\"";
        String sql1 = AbsRDBWorker.translateSql(sql, HttpServerRequest4Test.create(new HashMap<>(), new HashMap<>()), new HashMap<>());
        System.out.println(sql1);
        assertTrue(!sql1.contains("@{ABSHASH|'admin'}"));
        assertTrue(!sql1.contains("@{PBKDF|6,'123456'}"));
    }
    
    @Test
    public void testNeedCompile() {
        assertTrue(AbsDBWorker.needCompile("update t set a=@{a}, y=@[b] where id=1"));
        assertTrue(AbsDBWorker.needCompile("update t set a=@{a}, y='@[b]' where id=1"));
        assertTrue(!AbsDBWorker.needCompile("update t set a=@{a}, y='@{b}' where id=1"));
    }
}
