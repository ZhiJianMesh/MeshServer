package cn.net.zhijian.mesh.js;

import java.util.Calendar;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.quickjs.JavascriptMethod;
import cn.net.zhijian.quickjs.QuickJSContext;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 
 * @author flyinmind of csdn.net
 *
 */
public class JsEngineTest extends UnitTestBase {
    @Test
    public void testBase() throws Exception {
        String js = "var a='a',b='b';a+b;";
        String s = JsEngine.getString(js);
        assertTrue(s.equals("ab"));

        js = "var a=1,b=2;a+b;";
        int i = JsEngine.getInt(js);
        assertTrue(i == 3);

        js = "var a='a',b=`b\nc`;a+b;";
        s = JsEngine.getString(js);
        assertTrue(s.equals("ab\nc"));
    }
    
    @Test
    public void testSwitch() throws Exception {
        String js = "1>0?'test1':'test2'";
        String s = JsEngine.getString(js);
        assertTrue(s.equals("test1"));
        js = "1<0?'test1':'test2'";
        s = JsEngine.getString(js);
        assertTrue(s.equals("test2"));
    }

    @Test
    public void testPushJoin() throws Exception {
        String js = "var sql=['a'];sql.push('b','c','de');sql.join('')";
        String s = JsEngine.getString(js);
        assertTrue(s.equals("abcde"));
    }

    @Test
    public void testMesh() throws Exception {
        String js = "Mesh.error(RetCode.INTERNAL_ERROR, 'internal error');";
        HandleResult hr = JsEngine.getHandleResult(js);
        assertTrue(hr != null && hr.code == RetCode.INTERNAL_ERROR);

        js = "Mesh.success({a:1,b:'2'});";
        hr = JsEngine.getHandleResult(js);
        assertTrue(hr != null && hr.code == RetCode.OK);
        int a = ValParser.getAsInt(hr.data, "a");
        assertTrue(a == 1);
        String b = ValParser.getAsStr(hr.data, "b");
        assertTrue(b.equals("2"));
    }

    @Test
    public void testRetCode() throws Exception {
        String js = "var a=RetCode.OK;a";
        int code = JsEngine.getInt(js);
        assertTrue(code == RetCode.OK);
        js = "var a=RetCode.INTERNAL_ERROR;a";
        code = JsEngine.getInt(js);
        assertTrue(code == RetCode.INTERNAL_ERROR);
        js = "var a=RetCode.DATA_WRONG;a";
        code = JsEngine.getInt(js);
        assertTrue(code == RetCode.DATA_WRONG);
    }

    @Test
    public void testDB() throws Exception {
        String js = "DB.sqlError(5103,'error')";
        String s = JsEngine.getString(js);
        assertTrue(s.equals("{\"code\":5103,\"info\":\"error\"}"));

        js = "DB.sql(`select * from t where a='a' and b=1`)";
        s = JsEngine.getString(js);
        assertTrue(s.equals("select * from t where a='a' and b=1"));
    }
    
    @Test
    public void testFunction() throws Exception {
        String js = "(function(){return `select * from t where a='a' and b=1`})()";
        String s = JsEngine.getString(js);
        assertTrue(s.equals("select * from t where a='a' and b=1"));
    }
    
    @Test
    public void testHandleResult() throws Exception {
        String js = "(function(){return Mesh.success({})})()";
        HandleResult hr = JsEngine.getHandleResult(js);
        System.out.println("Result : " + hr.toString());
        assertTrue(hr.code == RetCode.OK);
        js = "(function(){return Mesh.error(RetCode.INTERNAL_ERROR, 'WRONG');})()";
        hr = JsEngine.getHandleResult(js);
        assertTrue(hr.code == RetCode.INTERNAL_ERROR && hr.info.equals("WRONG"));
        js = "Mesh.error(RetCode.INTERNAL_ERROR, 'WRONG');";
        hr = JsEngine.getHandleResult(js);
        assertTrue(hr.code == RetCode.INTERNAL_ERROR && hr.info.equals("WRONG"));
    }
    
    @Test
    public void testInnerFunction() throws Exception {
        String js = "function testFunc(){return true;}"
                + "if(testFunc())Mesh.success({});"
                + "else Mesh.error(RetCode.INTERNAL_ERROR, 'WRONG')";
        HandleResult hr = JsEngine.getHandleResult(js);
        assertTrue(hr.code == RetCode.OK);
        js = "function testFunc(){return false;}"
                + "if(testFunc())Mesh.success({});"
                + "else Mesh.error(RetCode.INTERNAL_ERROR, 'WRONG')";
        hr = JsEngine.getHandleResult(js);
        assertTrue(hr.code == RetCode.INTERNAL_ERROR);
        
        js = "function testFunc(){return false;}"
                + "(function(){if(testFunc())return Mesh.success({});"
                + "return Mesh.error(RetCode.INTERNAL_ERROR, 'WRONG');"
                + "})()";
        hr = JsEngine.getHandleResult(js);
        assertTrue(hr.code == RetCode.INTERNAL_ERROR);
    }
    
    @Test
    public void testAutoExecJsFunc() throws Exception {
        String js = "(function() {\n"
                + "var dbs=[{\"name\":\"192.168.1.117:8523\",\"ut\":1763982064163,\"val\":\"[{\\\"no\\\":1,\\\"slaves\\\":\\\"192.168.1.6:8523\\\",\\\"level\\\":1,\\\"shardEnd\\\":16382,\\\"shardStart\\\":0,\\\"type\\\":\\\"SQLITE\\\"},{\\\"no\\\":1,\\\"slaves\\\":\\\"192.168.1.6:8523\\\",\\\"level\\\":1,\\\"shardEnd\\\":32767,\\\"shardStart\\\":16383,\\\"type\\\":\\\"SQLITE\\\"}]\"},{\"name\":\"192.168.1.6:8523\",\"ut\":1763982064163,\"val\":\"[{\\\"no\\\":0,\\\"slaves\\\":\\\"\\\",\\\"level\\\":0,\\\"shardEnd\\\":32767,\\\"shardStart\\\":0,\\\"type\\\":\\\"SQLITE\\\"}]\"}];\n"
                + "var nodes,s;\n"
                + "var shardings={};\n"
                + "for(var d of dbs) { //addr(name)->cfg(val)\n"
                + "     nodes=JSON.parse(d.val);\n"
                + "     for(var n of nodes) {\n"
                + "        if(!(s = shardings[n.no])){\n"
                + "            s=new Array(32768).fill(0);\n"
                + "            shardings[n.no]=s;\n"
                + "        }\n"
//                + "Logger.info(k+',start:'+n.shardStart+',end:'+n.shardEnd)\n"
                + "        for(var i=n.shardStart;i<=n.shardEnd;i++) {\n"
                + "            if(s[i]!=0) {//重叠分片\n"
                + "                return Mesh.error(RetCode.DATA_WRONG, 'duplicate sharding, db '+d.name+','+n.no+'('+n.shardStart+'-'+n.shardEnd+')');\n"
                + "            }\n"
                + "            s[i]=1;\n"
                + "        }\n"
//                + "Logger.info(JSON.stringify(s))\n"
                + "     }\n"
                + "}\n"
                + "\n"
                + "for(var no in shardings) {//dbNo->sharding\n"
                + "      for(var s of shardings[no]) {\n"
                + "          if(s==0) { //未覆盖分片\n"
                + "              return Mesh.error(RetCode.DATA_WRONG, 'empty sharding,db '+no+'('+s+')');\n"
                + "          }\n"
                + "      }\n"
                + "}\n"
                + "return Mesh.success({});\n"
                + "})()";
        HandleResult hr = JsEngine.getHandleResult(js);
        System.out.println("info:" + hr.info);
        assertTrue(hr.code == RetCode.OK);
    }
    
    @Test
    public void testNewFunc() throws Exception {
        String js = "var f=new Function('s',"
                +"`if(s<=100)return 0; if(s>100)return s*0.1;`);"
                + "f(90)+'/'+f(500)";
        String s = JsEngine.getString(js);
        System.out.println("new Funnction():" + s);
        assertEquals(s, "0/50");
    }
    
    @Test
    public void testJsSpeed() throws Exception {
        String js;
        int N=100;
        HandleResult hr = null;
        long start = System.currentTimeMillis();
        for(int i = 0; i < N; i++) {
            js = "(function(){return Mesh.success({})})()";
            hr = JsEngine.getHandleResult(js);
        }
        long end = System.currentTimeMillis();
        assertTrue(hr != null && hr.code == RetCode.OK);
        System.out.println("speed1(auto executed):" + (1000L*N/(end - start)));
        start = System.currentTimeMillis();
        for(int i = 0; i < N; i++) {
            js = "Mesh.success({})";
            hr = JsEngine.getHandleResult(js);
        }
        end = System.currentTimeMillis();
        System.out.println("speed2(normal):" + (1000L*N/(end - start)));
        assertTrue(hr != null && hr.code == RetCode.OK);
    }
    
    @Test
    public void testJsRetCode() throws Exception {
        String js = "(function(){return RetCode.WRONG_PARAMETER})()";
        int retCode = JsEngine.getInt(js);
        assertTrue(retCode == RetCode.WRONG_PARAMETER);
        js = "RetCode.NO_RIGHT + RetCode.DATA_WRONG + RetCode.WRONG_PARAMETER";
        int v = JsEngine.getInt(js);
        assertTrue(v == RetCode.NO_RIGHT + RetCode.DATA_WRONG + RetCode.WRONG_PARAMETER);
    }
    
    @Test
    public void testJsDateFmt() throws Exception {
        String js = "var dt=new Date();"
                + " dt.getFullYear().toString().padStart(4, '0')"
                + " +dt.getMonth().toString().padStart(2, '0')"
                + " +dt.getDate().toString().padStart(2, '0')";
        String dt = JsEngine.getString(js);
        System.out.println("dt:" + dt);
        assertTrue(dt.length() == 8);
        js = "var dt=new Date();var t=dt.getTime()+86400000;dt.setTime(t);"
                + " dt.getFullYear().toString().padStart(4, '0')"
                + " +dt.getMonth().toString().padStart(2, '0')"
                + " +dt.getDate().toString().padStart(2, '0')";
        String dt1 = JsEngine.getString(js);
        System.out.println("dt1:" + dt1);
        assertTrue(dt1.compareTo(dt) > 0);
    }
    
    @Test
    public void testJsDateTimeZone() throws Exception {
        Calendar cal = Calendar.getInstance();
        String js = "var dt=new Date();dt.getHours();";
        int s = JsEngine.getInt(js);
        assertEquals(s, cal.get(Calendar.HOUR_OF_DAY));
        
        js = "var dt=new Date();"
            + "var hour=dt.getHours();"
            + "var t=dt.getTime()+dt.getTimezoneOffset()*60000;"//0区
            + "dt.setTime(t);"
            + "hour - dt.getHours()";
        s = JsEngine.getInt(js);
        assertEquals(s, cal.getTimeZone().getRawOffset() / 3600000);
    }
    
    @Test
    public void testJsonParamters() {
        String js = "var json=['{\"salaries\":['];\n"
                + "  var tasktimes={\"1\":0.4,\"2\":0.6}; //pid->ratio\n"
                + "  var salary=10000;\n"
                + "  var i=0;\n"
                + "  for(var t in tasktimes) {\n"
                + "      if(i>0) json.push(',');\n"
                + "      json.push('{\"pid\\\":',t,',\"val\":', (tasktimes[t]*salary).toFixed(2), '}')\n"
                + "      i++;"
                + "  }\n"
                + "  json.push(']}');\n"
                + "  json.join('');";
        String json = JsEngine.getString(js);
        System.out.println("js json:" + json);
        Map<String, Object> d = JsonUtil.jsonToMap(json);
        assertTrue(d != null && d.containsKey("salaries"));
    }
    
    @Test
    public void testJsonParse() {
        String js = "var json=`{a:1,b:2}`;"
                + "  json";
        String s = JsEngine.getString(js);
        assertEquals(s , "{a:1,b:2}");
        
        js = "var json=`{\"a\":1,\"b\":2}`;"
                + "  var map=JSON.parse(json);"
                + "  map.a";
        int v = JsEngine.getInt(js);
        assertEquals(v, 1);
        
        js = "var json=`{a:1,\"b\":2}`;" //字段名称必须加双引号
                + "  var map=JSON.parse(json);"
                + "  map.a";
        v = JsEngine.getInt(js);
        assertTrue(v != 1);
        
        js = "var json=`[{\"a\":1,\"b\":2},{\"a\":3,\"b\":5}]`;"
                + "  var arr=JSON.parse(json);"
                + "  arr[0].a";
        v = JsEngine.getInt(js);
        assertEquals(v, 1);

        
        js = "var json=`[{\"no\":1,\"slaves\":\"192.168.1.6:8523\",\"level\":1,\"shardEnd\":16382,\"shardStart\":0,\"type\":\"SQLITE\"},"
                +"{\"no\":2,\"slaves\":\"192.168.1.6:8523\",\"level\":1,\"shardEnd\":32767,\"shardStart\":16383,\"type\":\"SQLITE\"}]`;"
                + "  var arr=JSON.parse(json);"
                + "  arr[0].no+arr[1].no";
        v = JsEngine.getInt(js);
        assertEquals(v, 3);
    }

    public static class SecureTest {
        //private SecureTest() {}
        @JavascriptMethod
        public String sha256(String s) {
            return SecureUtil.sha256(s);
        }
    }
    
    @Test
    public void testUseJava() throws Exception {
        try(QuickJSContext qjc = QuickJSContext.create()) {
            qjc.getGlobalObject().setProperty("SecureTest", SecureTest.class);
            String s1 = SecureUtil.sha256("abc");
            Object s2 = qjc.evaluate("SecureTest.sha256('abc')");
            assertEquals(s1, s2);
        }
    }
}
