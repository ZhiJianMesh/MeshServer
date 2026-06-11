package cn.net.zhijian.mesh.frm.abs;

import java.util.List;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker.SqlType;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.js.JsEngine;

/**
 * 
 * @author flyinmind of csdn.net
 *
 */
public class AbsRDBWorkerTest extends UnitTestBase {
    String now = "@{#reqAt}";
    String wedgedStr = "update_time";

    @Test
    public void testModifyInsert() {
        String sql = "insert into mainreports(reportAt,price,orderNum)\n"
                + "values(1641804120402/7200000,100,1,@[!res])";
        String sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        assertTrue(judgeModifiedDMLSql(sql1));
        //multi-line insert
        sql = "insert into mainreports(reportAt,price,orderNum)\n"
                + "values(1641804120402/7200000,100,1,@[!res]),"
                + "(1641804120402/7200001,100,2,@[!res])";
        sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        assertTrue(judgeModifiedDMLSql(sql1));
    }

    @Test
    public void testModifyInsertWithQuot() {
        String sql = "insert into mainreports(reportAt,price,orderNum,comment)\n"
                + "values(1641804120402/7200000,100,1,@[!res],'(aaaaddd')";
        String sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        assertTrue(judgeModifiedDMLSql(sql1));
    }

    @Test
    public void testModifyInsertWithFor() {
        String sql = "insert into mainreports(reportAt,price,orderNum,comment) values\n"
                + "@{FOR|list[e,'s.!=','a'],`(1641804120402/7200000,100,1,@[!res],'(aaaaddd',`,e,`)`";
        String sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        System.out.println(sql1);
        assertTrue(judgeModifiedDMLSql(sql1));
        sql="insert or ignore into power(type,did,endT,power,account) values\n"
            + " ('SV',@{serviceId},@{CONST|i,max},'O','@{#tokenAcc}'),\n"
            + " ('CU',@{!customer},@{CONST|i,max},'F','@{#tokenAcc}'),\n"
            + " @{FOR|nextSigners, ``,\n"
            + "   `,('SV',@{serviceId},@{CONST|i,max},'W','`,e,`')`,\n"
            + "   `,('CU',@{!customer},@{CONST|i,max},'F','`,e,`')`\n"
            + " }";
        sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        System.out.println(sql1);
        assertTrue(judgeModifiedDMLSql(sql1));
    }

    @Test
    public void testModifyUpdate() {
        String sql = "update mainreports set price=0.01,orderNum=orderNum+1\n"
                + "where reportAt=1";
        String sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        assertTrue(judgeModifiedDMLSql(sql1));
    }
    
    @Test
    public void testModifyUpdateWithSubQueryInSet() {
        String sql = "update services set ver=(select max(ver) from vers where service={!serviceId})\n"
                + " where id=@{!serviceId}";
        String sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        assertTrue(judgeModifiedDMLSql(sql1));
    }
    
    @Test
    public void testModifyUpdateWithSubQueryInWhere() {
        String sql = "update reports set customer=customer+1 where account \n"
                + " in(select account from power where did=@{did} and type='CU' and power in('O','W'))"
                + " and reportAt=@{NOW|unit86400000}";
        String sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        assertTrue(judgeModifiedDMLSql(sql1));
    }

    @Test
    public void testModifyInsertSel() {
        String sql = "insert into mainreports(reportAt,price,orderNum)\n"
                + "select reportAt,sum(price),sum(orderNum) from mainreports where reportAt=111";
        String sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        assertTrue(judgeModifiedDMLSql(sql1));
    }

    @Test
    public void testModifyInsertSelNoFrom() {
        String sql = "insert into mainreports(reportAt,price,orderNum)\n"
                + "select reportAt,sum(price),sum(orderNum)";
        String sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        assertTrue(judgeModifiedDMLSql(sql1));
    }

    @Test
    public void testModifyScript() {
        String sql0 = "insert into t(a) values(b)";
        String script = "js:if(1>0) {DB.sql(`"+sql0+"`);} else {DB.sql(`update t set a=b`)}";
        String sql = JsEngine.getString(script.substring(IConst.JS_HEAD.length()));
        String sql1 = AbsRDBWorker.modifyDMLSql(sql, now); //js不修改，运行之后再修改
        assertTrue(judgeModifiedDMLSql(sql1));
    }

    @Test
    public void testModifyNoQuotScript() {
        String sql2 = "js:var sqls=['insert into tb(a,b,c,signer) values']\n"
                + "var vv=['a','b','c'];\n"
                + "for(var i in vv){\n"
                + "  if(i>0){sqls.push(',');}\n"
                + "  sqls.push(`(1,'e',2,'`);\n"
                + "  sqls.push(vv[i]);\n"
                + "  sqls.push(`')`)\n"
                + "}\n"
                + "DB.sql(sqls.join(''));";
        String sql = JsEngine.getString(sql2.substring(3));
        String sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        assertTrue(judgeModifiedDMLSql(sql1, 1));
        //System.out.println(sql);

        //Speed1:11494,Speed2:625000
//        final int N = 10000;
//        long start = System.currentTimeMillis();
//        for(int i = 0; i < N; i++) {
//            sql = JsEngine.getString(sql2.substring(3));
//            sql1 = AbstractRDBWorker.modifyDMLSql(sql, now);
//        }
//        long end =  System.currentTimeMillis();
//        System.out.println("Speed1:" + (1000L * N / (end - start)));
//
//        start = System.currentTimeMillis();
//        for(int i = 0; i < N; i++) {
//            sql1 = AbstractRDBWorker.modifyDMLSql(sql, now);
//        }
//        end =  System.currentTimeMillis();
//        System.out.println("Speed2:" + (1000L * N / (end - start)));
    }

    @Test
    public void testModifyComplexScript() {
        String script = "js:var sqls=['insert into flowlog(flow,did,step,result,turn,opinion,signer,update_time) values'];\n"
                + "      var signers=['a','b'];\n"
                + "      sqls.push(`(1,'222',0,1,0,'','aa',@{#reqAt})`);\n"
                + "      for(var i in signers) {\n"
                + "          sqls.push(`,(`,i,`,'dd',1,0,0,'','`,signers[i],`',@{#reqAt})`);\n"
                + "      }\n"
                + "      DB.sql(sqls.join(''));\n";
        String sql = JsEngine.getString(script.substring(IConst.JS_HEAD.length()));
        String sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        assertTrue(judgeModifiedDMLSql(sql1));

        sql = "js:if(1>0){\n"
            + "      DB.sql(`update pkgreports set logVal=@{val},logNum=logNum+1,orderBal=@[!total]\n"
            + "       where pkgId=@{!pkgId} and reportAt=@{NOW|unit86400000}`)\n"
            + "   } else {\n"
            + "      DB.sql(`insert into pkgreports(pkgId,reportAt,logVal,logNum,orderBal)\n"
            + "        values(@{!pkgId},@{NOW|unit86400000},@{val},@[!total])`)\n"
            + "}";
        sql = JsEngine.getString(sql);
        sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        assertTrue(judgeModifiedDMLSql(sql1));

        sql = "js:if(0>0){\n"
            + "      DB.sql(`update pkgreports set logVal=@{val},logNum=logNum+1,orderBal=@[!total]\n"
            + "       where pkgId=@{!pkgId} and reportAt=@{NOW|unit86400000}`)\n"
            + "   } else {\n"
            + "      DB.sql(`insert into pkgreports(pkgId,reportAt,logVal,logNum,orderBal)\n"
            + "        values(@{!pkgId},@{NOW|unit86400000},@{val},@[!total])`)\n"
            + "}";
        sql = JsEngine.getString(sql);
        sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        assertTrue(judgeModifiedDMLSql(sql1));
    }

    @Test
    public void testModifyMultipleSql() {
        String sql = "insert into flowlog(flow,did,step,result,turn,opinion,signer) values(1,2,3,4,5);\n"
                + "insert into flowlog(flow,did,step,result,turn,opinion,signer) values(1,2,3,4,5)";
        List<String> sqls = AbsRDBWorker.modifyDMLSqls(sql, now);
        assertEquals(sqls.size(), 2);
        assertTrue(judgeModifiedDMLSql(sqls.get(0)));
        assertTrue(judgeModifiedDMLSql(sqls.get(1)));
    }
    
    @Test
    public void testModifyJsMultipleSql() {
        String sql = "js:\n"
                + "if(1>0) {\n"
                + "    DB.sql(`insert into upmsgs(device,msg) values('@{code}','@{msg}');\n"
                + "    delete from upmsgs where device='@{code}' and update_time<@{oldest}`);\n"
                + "} else {\n"
                + "    DB.sql(`insert or ignore into errreqs(device,times) values('@{code}',0);\n"
                + "    update errreqs set times=times+1 where device='@{code}'`);\n"
                + "}";
        sql = JsEngine.getString(sql.substring(IConst.JS_HEAD.length()));
        List<String> sqls = AbsRDBWorker.modifyDMLSqls(sql, now);
        assertEquals(sqls.size(), 2);
        assertTrue(judgeModifiedDMLSql(sqls.get(0)));
        assertTrue(judgeModifiedDMLSql(sqls.get(1)));
    }

    @Test
    public void testModifyCreateSql() {
        String sql = "create table if not exists flow ( -- 工作流定义\n"
                + "                id       long not null primary key, -- 工作流id，abshash(service,name,ver)\n"
                + "                ver      int not null default 0, -- 版本号，从0开始，新建flow时，总是查询版本号最大的flow\n"
                + "                service  varchar(255) not null, -- 服务名称\n"
                + "                name     varchar(255) not null, -- 流程名称\n"
                + "                dispName varchar(255) not null, -- 显示名称\n"
                + "                comment  varchar(255) not null -- 描述\n"
                + ")";
        String sql1 = AbsRDBWorker.modifyDDLSql(sql);
        assertTrue(judgeModifiedDDLSql(sql1));
        sql = "create table if not exists step ( -- 工作流各步骤的定义\n"
                + "                flow     long not null, -- 工作流id，abshash(service,name,ver)\n"
                + "                step     int not null, -- 操作的步骤数，0为第一步\n"
                + "                -- 0:单人,1:会签，须全部OK；2:会签，全部签字，任一OK即可；3:会签，全部签字即可\n"
                + "                type     tinyint not null default 0,\n"
                + "                name     varchar(255) not null, -- 步骤名称\n"
                + "                -- 回调url，回调时回传step(100表示最后一步)、处理意见、下一步处理人、数据标识等参数\n"
                + "                callback varchar(255) not null,\n"
                + "                ext      text not null default '',\n"
                + "                primary key(flow,step)\n"
                + ")";
        sql1 = AbsRDBWorker.modifyDDLSql(sql);
        assertTrue(judgeModifiedDDLSql(sql1));
    }

    @Test
    public void testSelectSqlModify() {
        String sql = "select a,b from t where a=1";
        String sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
        assertEquals(sql, sql1);
    }

    @Test
    public void testRmvSqlComment() {
        String sql = "create table if not exists flow ( -- 工作流定义\n"
                + "                id       long not null primary key, -- 工作流id，abshash(service,name,ver)\n"
                + "                ver      int not null default 0, -- 版本号，从0开始，新建flow时，总是查询版本号最大的flow\n"
                + "                service  varchar(255) not null default '--', -- 服务名称\n"
                + "                name     varchar(255) not null, -- 流程名称\n"
                + "                dispName varchar(255) not null, -- 显示名称\n"
                + "                comment  varchar(255) not null -- 描述\n"
                + ") -- test--";
        String sql1 = AbsRDBWorker.removeComment(sql);
        System.out.println("remove comment:" + sql1);
        int pos = sql1.indexOf("'--'");
        assertTrue(pos > 0 && sql1.indexOf("--", pos + 4) < 0);
    }

    @Test
    public void testInsertOrIgnore() {
        String sql = "insert or ignore into mainreports(reportAt) values(@{NOW|unit7200000});\n"
                + "    update mainreports set logNum=logNum+1 where reportAt=@{NOW|unit7200000}";
        List<String> sqls = AbsRDBWorker.modifyDMLSqls(sql, now);
        assertEquals(sqls.size(), 2);
        assertTrue(judgeModifiedDMLSql(sqls.get(0), 1));
    }

    @Test
    public void testInsertComplexSql() {
        String sql = "replace into item(f,n,v) values(6,'t','[\n"
                + "[\n"
                + "  \"0.1.0\",\n"
                + "  \"create table if not exists flow ( -- 工作流定义\n"
                + "      id       long not null primary key, -- 工作流id，abshash(service,name,ver)\n"
                + "      ver      int not null default 0, -- 版本号，从0开始，新建flow时，总是查询版本号最大的flow\n"
                + "      service  varchar(255) not null, -- 服务名称\n"
                + "      name     varchar(255) not null, -- 流程名称\n"
                + "      dispName varchar(255) not null, -- 显示名称\n"
                + "      comment  varchar(255) not null -- 描述\n"
                + "  )\",\n"
                + "  \"create index if not exists idx_flow_service on flow(service,name,ver)\", -- 用于查询服务的流程\n"
                + "  \"create table if not exists flowbase ( -- 工作流基本信息\n"
                + "      flow     long not null, -- 工作流id\n"
                + "      step     int not null default 0, -- 当前在哪一步\n"
                + "      -- 回调参数，比如对应数据的ID，比如name:\"xx\",id:112，类似json，但是不能用{}包裹\n"
                + "      did      varchar(255) not null, -- 请求方发来的唯一标识，通常可以使用行主键id\n"
                + "      creator  varchar(255) not null, -- 创建人\n"
                + "      \n"
                + "      primary key(flow,did)\n"
                + "  )\"\n"
                + "]]')";
            String sql1 = AbsRDBWorker.modifyDMLSql(sql, now);
            assertTrue(judgeModifiedDMLSql(sql1, 1));
    }

    private boolean judgeModifiedDMLSql(String sql) {
        return judgeModifiedDMLSql(sql, 1);
    }

    private boolean judgeModifiedDMLSql(String sql, int num) {
        int pos = 0;
        for(int i = 0; i < num; i++) {
            pos = sql.indexOf(wedgedStr, pos);
            if(pos < 0) {
                System.out.println("modifyed sql==="+sql);
                return false;
            }
            pos = sql.indexOf(now, pos + wedgedStr.length() + 1);
            if(pos <= 0) {
                System.out.println("modifyed sql==="+sql);
                return false;
            }
        }
        return true;
    }

    private boolean judgeModifiedDDLSql(String sql) {
        int pos = 0;
        pos = sql.indexOf(wedgedStr, pos);
        if(pos < 0) {
            System.out.println("modifyed sql==="+sql);
            return false;
        }
        return true;
    }

    @Test
    public void testFindKeywords() {
        String sql = "js:if(@[!pkgNum]>0){\n"
                + "      DB.sql(\"update pkgreports set logVal=@{val},logNum=logNum+1,orderBal=@[!total]\n"
                + "       where pkgId=@{!pkgId} and reportAt=@{NOW|unit86400000}\")\n"
                + "   }";
        char[][] kws = new char[][] {"update".toCharArray(), "insert".toCharArray(), "delete".toCharArray()};
        int pos = AbsRDBWorker.findSqlKeyWordsInScript(sql, kws);
        assertTrue(pos > 0);
        sql = "js:if(@[!pkgNum]>0){\n"
                + "      DB.sql(`insert into pkgreports(pkgId,reportAt,logVal,logNum,orderBal)\n"
                + "        values(@{!pkgId},@{NOW|unit86400000},@{val},@[!total])`\n"
                + "}";
        pos = AbsRDBWorker.findSqlKeyWordsInScript(sql, kws);
        assertTrue(pos > 0);
        sql = "js:if(@[!pkgNum]>0){\n"
            + "    DB.sql('delete from pkgreports where pkgId=@{!pkgId}"
            + "}";
        pos = AbsRDBWorker.findSqlKeyWordsInScript(sql, kws);
        assertTrue(pos > 0);
        sql = "js:if(@[!pkgNum]>0){\n"
                + "    DB.sql('select * from pkgreports where pkgId=@{!pkgId}"
                + "}";
        pos = AbsRDBWorker.findSqlKeyWordsInScript(sql, kws);
        assertTrue(pos < 0);
    }


    /**
     * 一个sql中有多个分号分隔的多个子sql
     */
    @Test
    public void testModifySqls() {
        String sql1 = "update t set a=b where c=1";
        String sql = sql1 + "; delete from t where c=1;insert or ignore into t(a,b) select a,b from t1 where a=1";
        List<String> sqls = AbsRDBWorker.modifyDMLSqls(sql, now); //js不修改，运行之后再修改
        assertEquals(sqls.size(), 3);
        assertTrue(judgeModifiedDMLSql(sqls.get(0)));
        assertTrue(judgeModifiedDMLSql(sqls.get(1)));
        assertTrue(judgeModifiedDMLSql(sqls.get(2)));
    }
    
    /**
     * 一个sql中有多个分号分隔的多个子sql
     */
    @Test
    public void testSqlsWithComments() {
        String sql = "update t set a=b where c=1; -- test comment\n"
                + "delete from t where c=1; -- coment1;comment with semicolon;\n"
                + "-- coment2;comment with semicolon;\n"
                + "insert or ignore into t(a,b) select a,b from t1 where a=1\n";
        List<String> sqls = AbsRDBWorker.modifyDMLSqls(sql, now); //js不修改，运行之后再修改
        assertEquals(sqls.size(), 3);
        for(int i = 0; i < 3; i++) {
            assertTrue(!sqls.get(i).contains("--"));
            assertTrue(judgeModifiedDMLSql(sqls.get(i)));
        }
    }
    
    @Test
    public void testSelectIntoSql() {
        String sql = "select * from t1 into t2";
        SqlType t = AbsRDBWorker.getSqlType(sql);
        assertEquals(t, SqlType.ERROR);
        sql = "select * from t1 INTO t2";
        t = AbsRDBWorker.getSqlType(sql);
        assertEquals(t, SqlType.ERROR);
    }
    
    @Test
    public void testAddIgnore() {
        String sql = "insert into mainreports(reportAt,price,orderNum,comment)\n"
                + "values(1641804120402/7200000,100,1,@[!res],'(aaaaddd')";
        String sql1 = AbsRDBWorker.addInsertIgnore(sql);
        assertTrue(sql1.indexOf(" or ignore ") == 7);
    }
}
