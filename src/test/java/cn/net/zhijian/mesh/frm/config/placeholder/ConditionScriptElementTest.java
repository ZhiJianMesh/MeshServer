package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.util.Calculator;
import cn.net.zhijian.util.MapBuilder;

public class ConditionScriptElementTest extends ScriptElementTestUtil {
    @Test
    public void testCondition() {
        String s = "A:@{CONDITION|4,`i.>`,3,'1','0'},"
                + "B:@{CONDITION|s1,'s.<',s2,'ok','nok'},"
                + "C:@{CONDITION|d1,'d.>',d2,'ok',s1},"
                + "D:@{CONDITION|f1,'f.>=',f2,'ok','nok'},"
                + "E:@{CONDITION|f1,f.==,f2,'ok','nok'},"
                + "F:@{CONDITION|f1,'f.<=',f2,'ok','nok'},"
                + "G:@{CONDITION|f1,'f.>',f2,'ok','nok'},"
                + "H:@{CONDITION|f1,'f.!=',f2,'ok','nok'},"
                + "I:@{CONDITION|f1,'f.<',f2},"
                + "J:@{CONDITION|i1,'i.>=',i2,'B','S'},"
                + "K:@{CONDITION|i2,'i.>=',i1,!h,'S'}";
        Map<String, Object> params = new HashMap<>();
        params.put("s1", "n1");
        params.put("s2", "n2");
        params.put("d1", 2.4);
        params.put("d2", 3.3);
        params.put("f1", 2.4);
        params.put("f2", 2.4);
        params.put("i1", 1);
        params.put("i2", 6);
        
        Map<String, Object> resp = MapBuilder.of("h", "test");

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:1,B:ok,C:n1,D:ok,E:ok,F:ok,G:nok,H:nok,I:0,J:S,K:test");
    }

    @Test
    public void testConditionMatch() {
        String s = "A:@{CONDITION|s1,`s.~`,`n\\d+`,'true','false'},"
                + "B:@{CONDITION|s2,'s.!~',`n\\d{2}`},"
                + "C:@{CONDITION|'windows17','s.~',`windows1\\d`}";
        Map<String, Object> params = MapBuilder.of("s1", "n1", "s2", "n2");
        Map<String, Object> resp = new HashMap<>();

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:true,B:1,C:1");
        s = "A:@{CONDITION|s1,`i.~`,`n\\d+`,'true','false'},"
            + "B:@{CONDITION|s2,'s.!~',`n\\d{2}`}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        assertTrue(segs == null);
    }
    
    @Test
    public void testConditionIn() {
        String s = "A:@{CONDITION|s1,`s.@`,`a,b,c `,'true','false'},"
                + "B:@{CONDITION|s2,'s.!@',`a,b,c`},"
                + "C:@{CONDITION|'b','s.@',`a,b,c`},"
                + "D:@{CONDITION|s3,'s.@',`a,b,c`},"
                + "E:@{CONDITION|2,'i.@',`1,2 ,4`},"
                + "F:@{CONDITION|2.0,'i.@',`1.5,2.0,4`}";
        Map<String, Object> params = MapBuilder.of("s1", "a", "s2", "c ", "s3", "d");
        Map<String, Object> resp = new HashMap<>();

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:true,B:1,C:1,D:0,E:0,F:1");
    }
    
    @Test
    public void testConditionSize() {
        String s = "A:@{CONDITION|a1,`size.>`,1,'true','false'},"
                + "B:@{CONDITION|a1,'size.==',3},"
                + "C:@{CONDITION|a2,'o.!=',null},"
                + "D:@{CONDITION|a2,'size.>',0},"
                + "E:@{CONDITION|a2,'size.==',0},"
                + "F:@{CONDITION|a1,'size.<', 2}";
        Map<String, Object> params = MapBuilder.of("a1", Arrays.asList(1,2,3), "a2", null);
        Map<String, Object> resp = new HashMap<>();

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:true,B:1,C:0,D:0,E:1,F:0");
    }
    
    @Test
    public void testConditionNull() {
        String s = "A:@{CONDITION|o1,`o.==`,null,true,false},"
                + "B:@{CONDITION|o1, 'o.!=', o2, o1, o2},"
                + "C:@{CONDITION|'windows17','o.==',`windows17`,'ok', 'nok'},"
                + "D:@{CONDITION|!o3, 'o.==', null, 'isnull', 'notnull'},"
                + "E:@{CONDITION|null, 'o.!=', !o3, 'notnull', 'isnull'},"
                + "F:@{CONDITION|!o4, 'o.==', !o3, 'true', 'false'},"
                + "G:@{CONDITION|o, 'o.==', o1, 'true', 'false'},"
                + "H:@{CONDITION|!o5, 'o.==', null, true, false},"
                + "I:@{CONDITION|o2, 'o.==', null, true, false}";
        Map<String, Object> params = MapBuilder.of("o1", "n1", "o2", null, "o", "n1");
        Map<String, Object> resp = MapBuilder.of("o3", new Object(), "o4", null);

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:false,B:n1,C:ok,D:notnull,E:notnull,F:false,G:true,H:true,I:true");
    }

    @Test
    public void testConditionWithOthers() {
        String s = "A:@{CONDITION|s1,'s.<',s2, `@{CALCULATE|d.1,d1,'+',d2}`,d2},"
                + "B:@{CONDITION|4,`i.>`,3, `@{s1}`,s2},"
                + "C:@{CONDITION|d1,'d.>',d2, !r1, s1},"
                + "D:@{CONDITION|d1,'d.>',d2, s1, `@{!r1} @{!r2}`},"
                + "E:@{CONDITION|!r1,'s.==',!r2, `@{CALCULATE|d.1,d1,'+',d2}`,d2}";
        Map<String, Object> params = new HashMap<>();
        params.put("s1", "n1");
        params.put("s2", "n2");
        params.put("d1", 2.4);
        params.put("d2", 3.3);
       
        Map<String, Object> resp = MapBuilder.of("r1", "hello", "r2", "world");

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:5.7,B:n1,C:n1,D:hello world,E:3.3");
    }    
    
    @Test
    public void testFor() {
        String s = "A:@{FOR|el,`+`,'(',i,`,'`,e,`',@{!r})`}"
                + "B:@{FOR|list,`,`,'(',i,`,`,e.a,`,`,e.b,`,@{p},'','')`};"
                + "C:@{FOR|list,`,`,'(',i,`,`,e.a,`,`,e.b,`,`,e.c,')'};"
                + "D:@{FOR|list,`+`,'(',i,`,`,e.a,`,`,e.b,`,`,!r,')'};"
                + "E:@{FOR|list,`+`,'(',i,`,`,p,`,`,e.b,`,`,!r,')'}";
        Map<String, Object> o1 = MapBuilder.of("a", "n1", "b", "n2", "c", 1);
        Map<String, Object> o2 = MapBuilder.of("a", "m1", "b", "m2", "c", 2);
        List<Map<String, Object>> list = Arrays.asList(o1, o2);
        List<Object> l = Arrays.asList("1", 2);
        Map<String, Object> params = MapBuilder.of("list", list, "p", "x", "el", l);
        Map<String, Object> resp = MapBuilder.of("r", "test");

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:(0,'1',test)+(1,'2',test)"
                + "B:(0,n1,n2,x,'',''),(1,m1,m2,x,'','');"
                + "C:(0,n1,n2,1),(1,m1,m2,2);"
                + "D:(0,n1,n2,test)+(1,m1,m2,test);"
                + "E:(0,x,n2,test)+(1,x,m2,test)");
    }
    
    @Test
    public void testForWithOthers() {
        String s = "A:@{FOR|list,`;`,'(',i,`,`,e.a,`,`,e.b,`,@{IFVALID|p1,p1,'+',p2},'','')`}"
                + "B:@{FOR|list,`,`,'(',i,`,`,e.a,`,`,e.b,`,`,e.c,`,@{CONDITION|p1,'s.==', 'x', 'true', 'false'})`};"
                + "C:@{FOR|list,`,`,'(',i,`,`,e.a,`,`,e.b,`,`,e.c,`,@{p1},@{p2})`};"
                + "D:@{FOR|list,`,`,'(',i,`,`,e.a,`,`,e.b,`,`,e.c,`,`, p1, `,`, p2, `)`};";
        Map<String, Object> o1 = MapBuilder.of("a", "n1", "b", "n2", "c", 1);
        Map<String, Object> o2 = MapBuilder.of("a", "m1", "b", "m2", "c", 2);
        List<Map<String, Object>> list = Arrays.asList(o1, o2);
        Map<String, Object> params = MapBuilder.of("list", list, "p1", "x", "p2", "y'");
        Map<String, Object> resp = MapBuilder.of("r", "test");

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:(0,n1,n2,x+y'','','');(1,m1,m2,x+y'','','')"
                + "B:(0,n1,n2,1,true),(1,m1,m2,2,true);"
                + "C:(0,n1,n2,1,x,y''),(1,m1,m2,2,x,y'');" //p1 & @{p1}
                + "D:(0,n1,n2,1,x,y''),(1,m1,m2,2,x,y'');");
    }
    
    @Test
    public void testForWithFilters() {
        String s = "A:@{FOR|list[[e.a,'s.==','n1']||e.b,'s.==','m2'],`;`,'(',i,`,`,e.a,`,`,e.b,`,@{IFVALID|p1,p1,'+',p2},'','')`}"
                + "B:@{FOR|list[e.a, 's.!=', 'n1'],`,`,'(',i,`,`,e.a,`,`,e.b,`,`,e.c,`,@{CONDITION|p1,'s.==', 'x', 'true', 'false'})`};"
                + "C:@{FOR|!ol[e, 's.!=', '1' && [i,'i.>',0]],`,`,'(',i,`,'`,e,`')`};"
                + "D:@{FOR|!ol[e,'s.!=',#tokenAcc],`,`, i,`#`, e}";
        Map<String, Object> o1 = MapBuilder.of("a", "n1", "b", "n2", "c", 1);
        Map<String, Object> o2 = MapBuilder.of("a", "m1", "b", "m2", "c", 2);
        List<Object> ol = Arrays.asList(new Object[] {"2",1,"1","test"});
        List<Map<String, Object>> list = Arrays.asList(o1, o2);
        Map<String, Object> params = MapBuilder.of("list", list, "p1", "x", "p2", "y");
        params.put("#tokenAcc", "1");
        
        Map<String, Object> resp = MapBuilder.of("r", "test", "ol", ol);

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:(0,n1,n2,x+y,'','');(1,m1,m2,x+y,'','')"
                + "B:(1,m1,m2,2,true);"
                + "C:(3,'test');"
                + "D:0#2,3#test");
    }
    
    @Test
    public void testSwitchComplex() {
        String s = "A:@{SWITCH|i1,`i.>`,i2,'1',|,s1,'s.<=',s2,`0,2,@{s1}`|,'0','1',s1},"
                + "B:@{SWITCH|i1,`i.>`,i2,'1',|,s1,'s.>=',s2,'0',2,s1|,'0','1',s2},"
                + "C:@{SWITCH|i1,`i.<`,i2,'1',`''`,|,s1,'s.<=',s2,'0',2,s1|,'0','1',s2},"
                + "D:@{SWITCH|i1,`i.>`,i2,'1',|,s1,'s.<=',s2,'0',2,s1|,'0','1',s2}";
        Map<String, Object> params = MapBuilder.of("s1", "n1", "s2", "n2", "i1", 1);
        params.put("i2", 6);
        Map<String, Object> resp = MapBuilder.of("h", "test");

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:0,2,n1,B:01n2,C:1'',D:02n1");
    }
    
    @Test
    public void testSwitchSimple() {
        String s = "A:@{SWITCH|4,`i.>`,3,'1',|,'0'},"
                + "B:@{SWITCH|s1,'s.<',s2,'1',|,'2','3'},"
                + "C:@{SWITCH|d1,'d.>',d2,'ok',|,s1},"
                + "D:@{SWITCH|f1,'f.>=',f2,'ok',|,'nok'},"
                + "E:@{SWITCH|f1,f.==,f2,s1,'&',|,s2},"
                + "F:@{SWITCH|f1,'f.<=',f2,'ok',|,'nok'},"
                + "G:@{SWITCH|f1,'f.>',f2,'ok',|,'nok'},"
                + "H:@{SWITCH|f1,'f.!=',f2,'ok',|,'nok'},"
                + "I:@{SWITCH|f1,'f.<',f2,s1,|,s2},"
                + "J:@{SWITCH|i1,'i.>=',i2,'B',|,'S'},"
                + "K:@{SWITCH|i2,'i.>=',i1,!h,|,'S'},"
                + "L:@{SWITCH|s1,'o.==',null,!h,|,'S'}";
        Map<String, Object> params = new HashMap<>();
        params.put("s1", "n1");
        params.put("s2", "n2");
        params.put("d1", 2.4);
        params.put("d2", 3.3);
        params.put("f1", 2.4);
        params.put("f2", 2.4);
        params.put("i1", 1);
        params.put("i2", 6);
        
        Map<String, Object> resp = new HashMap<>();
        resp.put("h", "test");

        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:1,B:1,C:n1,D:ok,E:n1&,F:ok,"
                + "G:nok,H:nok,I:n2,J:S,K:test,L:S");
    }
    
    
    @Test
    public void testSeqidStep() { //CONDITION非常重要的一个使用场景
        String s = "@{SWITCH|!interval,'l.<',5000, !step, '*2',\n"
                + "|, !interval, 'l.<', 10000, !step,\n"
                + "|, !step, 'i.>', 10, !step, '/2',\n"
                + "|, !step}";
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = MapBuilder.of("interval", 100001, "step", 20);
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "20/2");
        
        resp = MapBuilder.of("interval", 500, "step", 10);
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "10*2");
        
        resp = MapBuilder.of("interval", 8000, "step", 10);
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "10");

        resp = MapBuilder.of("interval", 100002, "step", 10);
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "10");
    }
    
    @Test
    public void testWhenCondition() {
        String s, str;
        double v;
        ScriptElement[] segs;
        
        Map<String, Object> params = new HashMap<>();
        params.put("a", 1);
        params.put("b", "100");
        
        Map<String, Object> resp = new HashMap<>();
        resp.put("a", 2);
        resp.put("b", "101");
        
        s = "@{CONDITION|a,'i.>',!a}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        v = Calculator.calculate(str);
        assertEquals(v, 0.0, 0);

        s = "@{CONDITION|a,'s.<',!a}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        v = Calculator.calculate(str);
        assertTrue(v > 0);

        s = "@{CONDITION|a,'i.>',!a}+@{CONDITION|b,'i.<',!b}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        v = Calculator.calculate(str);
        assertTrue(v > 0);
        
        s = "@{CONDITION|a,'i.>',!a}*@{CONDITION|b,'i.<',!b}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        v = Calculator.calculate(str);
        assertEquals(v, 0.0, 0);
        
        s = "(@{CONDITION|a,'i.>',!a}*@{CONDITION|b,'i.<',!b})"
            + "   \n+ @{CONDITION|a,'i.<',!b}";
        segs = ScriptElement.parsePlaceHolder(s, params.keySet(), "'", "''");
        str = segementsToStr(params, resp, segs);
        v = Calculator.calculate(str);
        assertTrue(v > 0);
    }
}
