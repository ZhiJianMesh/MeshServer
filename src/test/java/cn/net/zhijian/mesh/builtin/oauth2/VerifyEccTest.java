package cn.net.zhijian.mesh.builtin.oauth2;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.IUtil;

/**
 * builtin 各 Verify 的 ECC 验签纯逻辑单元测试。
 * 覆盖：
 *  1) EccKeyPair.sign/verify 公开 API 的签名验签往返与抗篡改（company/user/oauth2 各 Verify 统一入口）；
 *  2) 模拟 oauth2 Verify 的 client_secret 验签流程（signSrc=caller-callee，用应用公钥验签），
 *     等价于 AuthBase.AppAuthInfo.verify 的底层逻辑。
 * 这是各类 Verify 验签的同一底层依赖，验证其正确性与抗篡改能力。
 */
public class VerifyEccTest extends UnitTestBase {

    @Test
    public void testEccSignVerifyRoundTrip() throws Exception {
        EccKeyPair kp = Ecc.instance(Ecc.CUR_VER).genKeyPair();
        String src = "caller-callee";
        byte[] signSrc = src.getBytes(IUtil.DEFAULT_CHARSET);
        byte[] sign = Ecc.instance(kp.ver).sign(signSrc, kp.prv);

        //对原文签名，用公钥验签应通过
        assertTrue(kp.verify(src, ByteUtil.bin2base64(sign)));
        //篡改原文后验签必须失败
        assertFalse(kp.verify("tampered", ByteUtil.bin2base64(sign)));
        //使用错误公钥验签必须失败
        EccKeyPair other = Ecc.instance(Ecc.CUR_VER).genKeyPair();
        assertFalse(other.verify(src, ByteUtil.bin2base64(sign)));
    }

    @Test
    public void testOauth2ClientSecretVerify() throws Exception {
        //模拟 oauth2 Verify.handle 中的验签：secret = Ecc.sign(caller + '-' + callee)
        EccKeyPair appKp = Ecc.instance(Ecc.CUR_VER).genKeyPair();

        String caller = "serviceA";
        String callee = "serviceB";
        byte[] signSrc = (caller + "-" + callee).getBytes(IUtil.DEFAULT_CHARSET);
        byte[] sign = Ecc.instance(appKp.ver).sign(signSrc, appKp.prv);

        //等价于 AppAuthInfo.verify(signSrc, sign)：用应用公钥验签，应通过
        assertTrue(Ecc.instance(appKp.ver).verify(signSrc, sign, appKp.pub));
        //scope 变化导致 signSrc 变化，验签必须失败
        byte[] wrongSrc = (caller + "-" + "serviceC").getBytes(IUtil.DEFAULT_CHARSET);
        assertFalse(Ecc.instance(appKp.ver).verify(wrongSrc, sign, appKp.pub));
    }
}
