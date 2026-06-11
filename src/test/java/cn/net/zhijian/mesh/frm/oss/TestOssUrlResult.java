package cn.net.zhijian.mesh.frm.oss;

import static cn.net.zhijian.UnitTestBase.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.oss.AbsOssClient.UrlRequest;
import cn.net.zhijian.util.ValParser;

public class TestOssUrlResult {
    @Test
    public void testUrlResult() {
        int size = 10000999;
        Map<String, String> headers = new HashMap<>();
        headers.put("k", "v");
        UrlRequest ur = new UrlRequest("t", "md5", "pwd", "/u",
                headers, System.currentTimeMillis(), size);
        Map<String, Object> urm = ur.toMap();
        assertTrue(urm.get("obj").equals("t")
                && urm.get("md5").equals("md5")
                && urm.get("pwd").equals("pwd")
                && urm.get("url").equals("/u")
                && urm.get("size").equals(size)
                && ValParser.getAsObject(urm, "headers").get("k").equals("v"));
        ur = UrlRequest.fromMap(urm);
        assertTrue(ur.objName.equals("t")
                && ur.md5.equals("md5")
                && ur.pwd.equals("pwd")
                && ur.url.equals("/u")
                && urm.get("size").equals(size)
                && ur.headers.get("k").equals("v"));
    }
}
