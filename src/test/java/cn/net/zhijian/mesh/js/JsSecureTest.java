package cn.net.zhijian.mesh.js;

import static cn.net.zhijian.UnitTestBase.assertEquals;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.Ecc.EccKeyPair;

/**
 * 
 * @author flyinmind of csdn.net
 *
 */
public class JsSecureTest {
    static JsSecure secure = new JsSecure();

    @Test
    public void testKeypair() throws Exception {
        String pwd = "112456777";
        String kp = secure.keyPair(pwd);
        System.out.println("kp:" + kp);
        String plain = "test123654";
        String cipher = secure.keyPairEncrypt(kp, plain);
        System.out.println("cipher:" + cipher);
        String s = secure.keyPairDecrypt(kp, pwd, cipher);
        System.out.println("s:" + s + ",plain:" + plain);
        assertEquals(s, plain);
    }

    @Test
    public void testJsEcc() throws Exception {
        Ecc ecc = Ecc.instance();
        String pwd = "1234";
        EccKeyPair kp = ecc.genKeyPair();
        String sKp = kp.toString(pwd);
        String pub = secure.publicKey(sKp);
        String prv = secure.privateKey(sKp, pwd);
        String plain = "test90000001111";
        String cipher = secure.eccEncrypt(plain, pub);
        
        String s = secure.eccDecrypt(cipher, prv);
        System.out.println("cipher:" + cipher + ",s:" + s + ",plain:" + plain);
        assertEquals(s, plain);
        s = secure.keyPairDecrypt(sKp, pwd, cipher);
        assertEquals(s, plain);
    }

    @Test
    public void testAesCbc() throws Exception {
        String plain = "test90000001111";
        String pwd = "test1111";
        String cipher = secure.cbcEncrypt(plain, pwd);
        String s = secure.cbcDecrypt(cipher, pwd);
        assertEquals(s, plain);
    }
    
    @Test
    public void testGcmEncryptDecrypt() throws Exception {
        String plain = "test90000001111";
        String pwd = "test1111";
        String cipher = secure.gcmEncrypt(plain, pwd);
        String s = secure.gcmDecrypt(cipher, pwd);
        assertEquals(s, plain);
    }
    
    @Test
    public void testChangeEccPwd() throws Exception {
        String plain = "test90000001111";
        String pwd = "test1111";
        String newPwd = "1111test1100";
        String kp = secure.keyPair(pwd);
        String cipher = secure.keyPairEncrypt(kp, plain);
        String s = secure.keyPairDecrypt(kp, pwd, cipher);
        assertEquals(s, plain);
        kp = secure.changeKeyPairPwd(kp, pwd, newPwd);
        cipher = secure.keyPairEncrypt(kp, plain);
        s = secure.keyPairDecrypt(kp, newPwd, cipher);
        assertEquals(s, plain);
    }
    
    @Test
    public void testJsEccEncryptDecrypt() {
        String plain = "中国test";
        String pwd = "1234765";
        String kp = secure.keyPair(pwd);
        String cipher = secure.keyPairEncrypt(kp, plain);
        String txt = secure.keyPairDecrypt(kp, pwd, cipher);
        System.out.println("kp:" + kp + "|cipher:" + cipher + "|txt:" + txt);
        assertEquals(txt, plain);
        //以下内容是c#中生成，用以检验两种环境的兼容性
        kp = "179MGyONecRvvOWV3MGQaM7NxPafXWxnyQKyJstt7LjbUh3T+UhaqWEOtT3+/LMYgop6dSQQhXT9IroCDivNIWQ==&IB9vdJD277lL/jFhx8rszXjECUvFDDkgUcNccjaJwMJ9AKaXHlNLLMnhcwhNC+hHfalsC50bbNMFY5UV8gEDkmVd";
        cipher = "4qw9fUZJsmY0I_4r_tBVLjMXKfHIFhQMQav7E4BgMefaLYIedl3KlYidL4EbWaFDCzPc4h5vIAIZfH4qzE7-rt_wS-JP1r6z1-tL4_hav-kOWgEsE9sDRmO87GnspYOncZ4MfBgl3FV-nWasqSfFQs0@Cy9wvxyeZNf5Lj9ySK_M8RUjoWy8QK7yj0jVS50HPQ6";

        txt = secure.keyPairDecrypt(kp, pwd, cipher);
        assertEquals(txt, plain);
    }
}
