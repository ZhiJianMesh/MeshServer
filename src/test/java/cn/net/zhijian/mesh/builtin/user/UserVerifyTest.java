package cn.net.zhijian.mesh.builtin.user;

import java.util.List;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.tokenworker.CodebookTokenWorker;

/**
 * user 验签纯逻辑测试：端侧用户采用 OAuth 密码本（codebook）签名。
 * 覆盖：CodebookTokenWorker 签发用户 token，AccessToken.parse 用同一密码本验签通过；
 * 篡改 token 后验签失败。这是 UserVerify 在用户认证环节校验 token 的核心纯逻辑。
 */
public class UserVerifyTest extends UnitTestBase {

    @Test
    public void testCodebookTokenVerify() throws Exception {
        String cbs = CodebookTokenWorker.generateCodebooks(3);
        List<byte[]> codebooks = CodebookTokenWorker.parseCodebooks(cbs);
        CodebookTokenWorker tw = new CodebookTokenWorker(codebooks);

        AccessToken at = new AccessToken(tw, 0, "user1", "userSrv", "1",
                IOAuth.SIGNTYPE_CODEBOOK, IOAuth.TOKENTYPE_USER);
        String token = at.generate();
        assertNotNull(token);

        AccessToken parsed = AccessToken.parse(token, tw);
        assertNotNull(parsed);
        assertEquals("user1", parsed.caller);

        //篡改 token 验签必须失败
        String tampered = token.substring(0, token.length() - 1)
                + (token.charAt(token.length() - 1) == 'A' ? 'B' : 'A');
        assertNull(AccessToken.parse(tampered, tw));
    }
}
