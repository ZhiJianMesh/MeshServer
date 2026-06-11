package cn.net.zhijian.mesh.client;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.frm.intf.IConst;

public class BiosClientTest extends UnitTestBase {
    @Test
    public void testAppToken() {
        String token = BiosClient.appToken(testService, IConst.SERVICE_COMPANY, "*", 3600);
        AccessToken at = AccessToken.parse(token, testService.tokenWorker);
        long expiresIn = at == null ? 0 : at.expiresAt() - System.currentTimeMillis();
        System.out.println("Expires in " + expiresIn);
        assertTrue(at != null &&  expiresIn > 3000 * 1000);
        
        token = BiosClient.appToken(testService, IConst.SERVICE_COMPANY, "*"); //使用了缓存
        at = AccessToken.parse(token, testService.tokenWorker);
        expiresIn = at.expiresAt() - System.currentTimeMillis();
        assertTrue(expiresIn < 3600 * 1000);
        
        token = BiosClient.appToken(testService, IConst.SERVICE_APPSTORE, "*"); //改变被调，重新生成
        at = AccessToken.parse(token, testService.tokenWorker);
        assertEquals(at.expiresAt(), 0L);
    }
}
