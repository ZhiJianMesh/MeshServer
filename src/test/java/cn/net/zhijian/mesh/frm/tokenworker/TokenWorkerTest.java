package cn.net.zhijian.mesh.frm.tokenworker;

import static cn.net.zhijian.UnitTestBase.assertTrue;
import static cn.net.zhijian.UnitTestBase.fail;

import java.io.IOException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.StringUtil;

public class TokenWorkerTest {
    private static final String app_public_key = "IGBUd7rWjpgFXrqaBUo+f69NXDYJpGkpIu/qkn5Ap9qsO3Er94bIvo/rrVI3NUAQHDw8/7lVgo6qv0rbgb7mx6A=";
    private static final String app_private_key = "ZMZmKAuGiWHUYQDprrDlMa6/32HQN8YJ5om0oDqBQEU=";
    private static EccTokenWorker appTokenWorker;

    @BeforeAll
    public static void testInitTokenWorker() {
        Ecc ecc = Ecc.instance();
        try {
            PrivateKey appPriKey = ecc.str2PrivateKey(app_private_key);
            PublicKey appPubKey = ecc.publicKey(ByteUtil.stdBase64Decode(app_public_key));
            appTokenWorker = new EccTokenWorker(ecc.ver, appPriKey, appPubKey, IOAuth.TOKENTYPE_SERVICE);
        } catch (Exception e) {
            fail(StringUtil.exception2Str(e));
        }  
    }
    
    @Test
    public void testKeyTokenSignVerify() {
        assertTrue(appTokenWorker != null);
        String src = "crm-sv:user";
        byte[] b0 = src.getBytes(IConst.DEFAULT_CHARSET);
        byte[] signBytes = new byte[b0.length + appTokenWorker.identifyLen()];
        System.arraycopy(b0, 0, signBytes, 0, b0.length);

        byte[] b = appTokenWorker.sign(signBytes);
        String signStr = ByteUtil.bin2base64(b);
        byte[] b1 = ByteUtil.base642bin(signStr);
        assertTrue(ByteUtil.byteArrayEquals(b, 0, b1, 0, b1.length));
        assertTrue(appTokenWorker.verify(signBytes, b1));
    }
    
    @Test
    public void testPwdTokenWorker() {
        ITokenWorker tw = new PwdTokenWorker(StringUtil.genRandomCode(10));
        String src = "crm-sv:user";
        byte[] b0 = src.getBytes(IConst.DEFAULT_CHARSET);
        byte[] signBytes = new byte[b0.length + tw.identifyLen()];
        System.arraycopy(b0, 0, signBytes, 0, b0.length);
        byte[] b1 = tw.sign(signBytes);
        assertTrue(tw.verify(signBytes, b1));
    }
    
    @Test
    public void testCodebookTokenWorker() throws IOException {
        String cbs1 = CodebookTokenWorker.generateCodebooks(3);
        List<byte[]> cbs = CodebookTokenWorker.parseCodebooks(cbs1);
        
        ITokenWorker tw = new CodebookTokenWorker(cbs);
        String src = "crm-sv:user";
        byte[] b0 = src.getBytes(IConst.DEFAULT_CHARSET);
        byte[] signBytes = new byte[b0.length + tw.identifyLen()];
        System.arraycopy(b0, 0, signBytes, 0, b0.length);
        byte[] b1 = tw.sign(signBytes);
        assertTrue(tw.verify(signBytes, b1));
    }
}
