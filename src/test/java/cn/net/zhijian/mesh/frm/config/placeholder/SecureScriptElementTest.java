package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.util.Ecc.EccKeyPair;

public class SecureScriptElementTest extends ScriptElementTestUtil {

    @Test
    public void testSecure() {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        String p1 = "a'be";
        params.put("p1", p1);
        params.put("p2", "Adb?");
        params.put("p3", null);
        Set<String> paraKeys = params.keySet();

        String s = "A:@{SHA256|p1,p2},C:@{md5|p1}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "A:2kbXns0RmsKc0wBquu8jruR1wUnVc8plhviP6BZSiG1,C:QA7uNor2PSdHx_0q16RuW2");

        s = "UUID64:@{UUID}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str.length(), 29);

        s = "UUID16:@{UUID|16}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str.length(), 39);

        s = "HMACSHA256:@{HMACSHA256|p1,p2}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str.length(), 75);

        s = "pbkdf1:@{PBKDF|p1,p2}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        assertTrue(segs != null && segs.length > 0);
        str = segementsToStr(params, resp, segs);
        assertEquals(str.length(), 57);

        s = "pbkdf2:@{PBKDF|6,p1,p2}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str.length(), 57);
        
        s = "@{PBKDF|1000,p1}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String kdf = segementsToStr(params, resp, segs);
        
        params.put("kdf", kdf);
        s = "@{PBKDFCHECK|p1,kdf}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        str = segementsToStr(params, resp, segs);
        assertEquals(str, "true");
    }
    
    @Test
    public void testEcKeyPairNoPwd() throws Exception {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        String p1 = "abc";
        params.put("p1", p1);
        Set<String> paraKeys = params.keySet();
        
        String s = "@{ECKEYPAIR}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        assertTrue(segs != null && segs.length > 0);
        String kp = segementsToStr(params, resp, segs);
        params.put("kp", kp);
        
        EccKeyPair rawKp = EccKeyPair.parse(kp);
        s = "@{ECKEYPAIR|encode,kp,p1}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String encP1 = segementsToStr(params, resp, segs);
        s = "@{ECKEYPAIR|sign,kp,p1}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String signP1 = segementsToStr(params, resp, segs);
        s = "A:@{ECKEYPAIR|public,kp}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        assertTrue(segs != null && segs.length > 0);
        s = segementsToStr(params, resp, segs);
        assertEquals(s, "A:" + rawKp.publicKey2Str());
        params.put("eP1", encP1);
        params.put("sP1", signP1);
        s = "A:@{ECKEYPAIR|decode,kp,eP1},"
            + "B:@{ECKEYPAIR|verify,kp,p1,sP1},"
            + "C:@{ECKEYPAIR|verify,kp,'abdcd',sP1}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        assertTrue(segs != null && segs.length > 0);
        s = segementsToStr(params, resp, segs);
        assertEquals(s, "A:" + p1 + ",B:true,C:false");        
    }

    @Test
    public void testEcKeyPairWithPwd() throws Exception {
        Map<String, Object> params = new HashMap<>();
        String pwd = "dll##434llll223cf";
        Map<String, Object> resp = Map.of("pwd", pwd);
        String p1 = "abc";
        params.put("p1", p1);
        Set<String> paraKeys = params.keySet();
        
        String s = "@{ECKEYPAIR|new,!pwd}";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        assertTrue(segs != null && segs.length > 0);
        String kp = segementsToStr(params, resp, segs);
        params.put("kp", kp);
        
        EccKeyPair rawKp = EccKeyPair.parse(kp, pwd);
        s = "@{ECKEYPAIR|encode,kp,p1,!pwd}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String encP1 = segementsToStr(params, resp, segs);
        s = "@{ECKEYPAIR|sign,kp,p1,!pwd}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String signP1 = segementsToStr(params, resp, segs);
        s = "A:@{ECKEYPAIR|public,kp,!pwd}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        assertTrue(segs != null && segs.length > 0);
        s = segementsToStr(params, resp, segs);
        assertEquals(s, "A:" + rawKp.publicKey2Str());
        params.put("eP1", encP1);
        params.put("sP1", signP1);
        s = "A:@{ECKEYPAIR|decode,kp,eP1,!pwd},"
            + "B:@{ECKEYPAIR|verify,kp,p1,sP1,!pwd},"
            + "C:@{ECKEYPAIR|verify,kp,'abdcd',sP1,!pwd}";
        segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        assertTrue(segs != null && segs.length > 0);
        s = segementsToStr(params, resp, segs);
        assertEquals(s, "A:" + p1 + ",B:true,C:false");        
    }

    
    @Test
    public void testInjectionAttack() {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> resp = new HashMap<>();
        String p1 = "a' or '1'='1";
        params.put("p1", p1);
        params.put("p2", "b' or ''='");
        Set<String> paraKeys = params.keySet();

        String s = "select id from user where account='@{p1}' and pwd='@{p2}'";
        ScriptElement[] segs = ScriptElement.parsePlaceHolder(s, paraKeys, "'", "''");
        String str = segementsToStr(params, resp, segs);
        assertEquals(str, "select id from user where account='a'' or ''1''=''1' and pwd='b'' or ''''='''");
    }


}
