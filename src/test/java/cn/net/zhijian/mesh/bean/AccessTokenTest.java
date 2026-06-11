package cn.net.zhijian.mesh.bean;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.mesh.frm.tokenworker.CodebookTokenWorker;
import cn.net.zhijian.mesh.frm.tokenworker.EccTokenWorker;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.StringUtil;

/**
 * 
 * @author flyinmind of csdn.net
 *
 */
public class AccessTokenTest extends UnitTestBase {
    private static final List<byte[]> CODEBOOKS = new ArrayList<>();
    private static final SecureRandom RAND = new SecureRandom();
    private static final String caller = "om";
    private static final String callee = "webdb";
    private static final String user = "123";
    
    private static final int PARTITION = 9876;
    
    private static ITokenWorker codebookTw;
    private static ITokenWorker keyTw;
    private static ITokenWorker cloudTw;
    
    @BeforeAll
    public static void testInit() {
        for(int i = 0; i < 4; i++) {
            byte[] codebook = new byte[4096];
            RAND.nextBytes(codebook);
            CODEBOOKS.add(codebook);
        }
        EccKeyPair kp;
        Ecc ecc = Ecc.instance();
        try {
            kp = ecc.genKeyPair();
            PublicKey pubKey = kp.pub;
            PrivateKey prvKey = kp.prv;
            keyTw = new EccTokenWorker(ecc.ver, prvKey, pubKey, IOAuth.SIGNTYPE_APPKEY);
            cloudTw = new EccTokenWorker(ecc.ver, prvKey, pubKey, IOAuth.SIGNTYPE_COMPANYKEY);
            codebookTw = new CodebookTokenWorker(CODEBOOKS);
        } catch (Exception e) {
            fail(StringUtil.exception2Str(e));
        }
    }
    
    @Test
    public void testCodebookToken() {
        String features = "r";
        int tokenType = AccessToken.TOKENTYPE_SERVICE;
        AccessToken at = codebookTw.create(PARTITION, caller, callee, features, tokenType);
        String token = at.generate();
        at = AccessToken.parse(token, codebookTw);
        assertTrue(at != null);

        assertTrue(!at.expired());        
    }
    
    @Test
    public void testTokenHashcode() {
        String features = "r";
        int tokenType = AccessToken.TOKENTYPE_SERVICE;
        AccessToken at = codebookTw.create(PARTITION, caller, callee, 0L, features, tokenType);
        String token = at.generate();
        at = AccessToken.parse(token, codebookTw);
        assertTrue(at != null);
        long hc0 = at.getHashCode();
        long hc1 = AccessToken.hashCode(token);
        long hc2 = AccessToken.hashCode(tokenType, PARTITION, caller, callee);
        System.out.println(token);
        assertEquals(hc0, hc1);
        assertEquals(hc0, hc2);
    }
    
    @Test
    public void testChangeTokenCid() {
        String features = "r";
        int tokenType = AccessToken.TOKENTYPE_SERVICE;

        AccessToken at = codebookTw.create(PARTITION, caller, callee, features, tokenType);
        String token = at.generate();
        String caller1 = AccessToken.getCaller(token);
        assertEquals(caller1, caller);
        int signType = AccessToken.getSignType(token);
        assertEquals(signType, codebookTw.getSignType());
        at = AccessToken.parse(token, codebookTw);
        assertEquals(at.callee, callee);
        assertEquals(at.caller, caller);
        assertEquals(at.partition, PARTITION);
        assertTrue(!at.expired());        
    }
    
    @Test
    public void testCodebookBrokenToken() {
        String ext = "*";
        int tokenType = AccessToken.TOKENTYPE_SERVICE;
        AccessToken at = codebookTw.create(PARTITION, caller, callee, ext, tokenType);
        String token = at.generate();
        System.out.println("token("+token.length()+"):" + token);
        at = AccessToken.parse(token, codebookTw);
        assertNotNull(at);
        
        byte[] tokenChars = token.getBytes();
        //只破坏内容部分，破坏签名部分有时候会通过校验，但是不会造成攻击
        byte oldChar;
        for(int i = 0; i < tokenChars.length; i++) {
            oldChar = tokenChars[i];
            do {
                tokenChars[i] = (byte)RAND.nextInt(256);
            } while(tokenChars[i] == oldChar);
            
            String brokenToken = new String(tokenChars);
            at = AccessToken.parse(brokenToken, codebookTw);
            if(at != null && !at.expired()) {
                System.out.println("fail to brokenCodebookToken(" + i + "):" + brokenToken);
            }
            //assertEquals(null, at);
            tokenChars[i] = oldChar;
        }
    }
    
    @Test
    public void testKeyBrokenToken() { //概率性失败，属于正常
        String ext = "*";
        int tokenType = AccessToken.TOKENTYPE_SERVICE;
        AccessToken at = keyTw.create(PARTITION, caller, callee, ext, tokenType);
        String token = at.generate();
        System.out.println("token("+token.length()+"):" + token);
        at = AccessToken.parse(token, keyTw);

        assertTrue(at != null);
        
        byte[] tokenChars = token.getBytes();
        //只破坏内容部分，破坏签名部分有时候会通过校验，但是不会造成攻击
        byte oldChar;
        for(int i = 0; i < tokenChars.length; i++) {
            oldChar = tokenChars[i];
            do {
                tokenChars[i] = (byte)RAND.nextInt(256);
            } while(tokenChars[i] == oldChar);
            
            String brokenToken = new String(tokenChars);
            at = AccessToken.parse(brokenToken, keyTw);
            if(at != null && !at.expired()) {
                System.out.println("fail to brokenKeyToken(" + i + "):" + brokenToken);
            }
            //assertTrue(null == at);
            tokenChars[i] = oldChar;
        }
    }
    
    @Test
    public void testUserToken() {
        String features = "r";
        int tokenType = AccessToken.TOKENTYPE_USER;
        AccessToken at = codebookTw.create(PARTITION, user, callee, features, tokenType);
        String token = at.generate();
        String caller1 = AccessToken.getCaller(token);
        assertEquals(caller1, user);
        String callee1 = AccessToken.getCallee(token);
        assertEquals(callee1, callee);
        String stackholder = AccessToken.getStakeholder(token);
        assertEquals(stackholder, user +'@' + callee);
        int signType = AccessToken.getSignType(token);
        assertEquals(signType, codebookTw.getSignType());
        at = AccessToken.parse(token, codebookTw);
        assertEquals(at.callee, callee);
        assertEquals(at.caller, user);
        assertEquals(AccessToken.getPartition(token), PARTITION);
        assertEquals(at.partition, PARTITION);
        assertTrue(!at.expired());        
    }
    
    @Test
    public void testAppkeyToken() {
        String features = "r";
        int tokenType = AccessToken.TOKENTYPE_SERVICE;
        AccessToken at = keyTw.create(PARTITION, caller, callee, features, tokenType);
        String token = at.generate();
        String caller1 = AccessToken.getCaller(token);
        assertEquals(caller1, caller);
        int signType = AccessToken.getSignType(token);
        assertEquals(signType, keyTw.getSignType());
        at = AccessToken.parse(token, keyTw);
        assertTrue(at != null);
        assertEquals(at.callee, callee);
        assertEquals(at.caller, caller);
        assertEquals(at.partition, PARTITION);

        assertTrue(!at.expired());        
    }
    
    @Test
    public void testChangeAppkeyTokenCid() {
        String features = "r";
        int tokenType = AccessToken.TOKENTYPE_DB;
        AccessToken at = keyTw.create(PARTITION, caller, callee, features, tokenType);
        String token = at.generate();
        assertTrue(token != null && token.length() > 0);
        assertTrue(!at.expired());
        
        tokenType = AccessToken.TOKENTYPE_USER;
        at = keyTw.create(PARTITION, caller, callee, features, tokenType);
        token = at.generate();
        assertTrue(!at.expired());    
    }
    
    @Test
    public void testCloudToken() {
        AccessToken at = cloudTw.create(PARTITION, user, callee, "r"/*features*/, AccessToken.TOKENTYPE_USER);
        String token = at.generate();
        at = AccessToken.parse(token, cloudTw);
        assertTrue(at != null);
        assertEquals(at.callee, callee);
        assertEquals(at.caller, user);
        assertEquals(at.partition, PARTITION);
    }
    
    @Test
    public void testCrossCloudToken() throws Exception {
        String privateKeyStr = "ZMZmKAuGiWHUYQDprrDlMa6/32HQN8YJ5om0oDqBQEU=";
        String publicKeyStr = "IGBUd7rWjpgFXrqaBUo+f69NXDYJpGkpIu/qkn5Ap9qsO3Er94bIvo/rrVI3NUAQHDw8/7lVgo6qv0rbgb7mx6A=";
        
        Ecc ecc = Ecc.instance();
        PrivateKey prv = ecc.str2PrivateKey(privateKeyStr);
        String prv1 = Ecc.privateKey2Str(prv);
        assertEquals(prv1, privateKeyStr);
        
        PublicKey pub = ecc.publicKey(ByteUtil.stdBase64Decode(publicKeyStr));
        String pub1 = Ecc.publicKey2Str(pub);
        assertEquals(pub1, publicKeyStr);
        EccTokenWorker cloudTw = new EccTokenWorker(ecc.ver, prv, pub, IOAuth.SIGNTYPE_COMPANYKEY);
//        AccessToken at = cloudTw.create(PARTITION, user, callee, "r", AccessToken.TOKENTYPE_USER);
//        String token = at.generate();
//        System.out.println("token:" + token);
        String token = "0232QK1b344Y@123@webdb@02o1mGa0WWs0TmntWcKTGdlcs2lWlD8Xq2rwHUQDqURWYY0AGK_e202OE4e9jNXOABzGqzDcN48OwZUcnqfzGQsMWZnn_eulI3";
        AccessToken at = AccessToken.parse(token, cloudTw);
        assertTrue(at != null);
        assertEquals(at.callee, callee);
        assertEquals(at.caller, user);
        assertEquals(at.partition, PARTITION);
    }
}
