package cn.net.zhijian.mesh.oss;

import static cn.net.zhijian.util.HttpUtil.HEAD_CONTENT_TYPE;
import static cn.net.zhijian.util.HttpUtil.HEAD_HOST;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.IUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;

/**
 * AliOssClient 单元测试。
 * 覆盖 getUrl/putUrl 的 HMAC-SHA1 签名、直链 URL 参数、请求头构造，
 * 以及 AbsOssClient.predictedHttpTime / UrlRequest 序列化——均为纯逻辑，不发起网络。
 */
public class AliOssClientTest extends UnitTestBase {
    private static final String KEY_ID = "test-key-id";
    private static final String KEY = "test-secret-key";
    private static final String BUCKET = "mybucket";
    private static final String INNER = "oss-cn-internal.aliyuncs.com";
    private static final String OUTER = "oss-cn.aliyuncs.com";
    private final AliOssClient client = new AliOssClient(INNER, OUTER, BUCKET, KEY_ID, KEY);

    private static Map<String, String> parseQuery(String url) {
        Map<String, String> m = new HashMap<>();
        int q = url.indexOf('?');
        if(q < 0) {
            return m;
        }
        for(String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if(eq > 0) {
                m.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return m;
    }

    private static String hmacB64(String signData, String key) {
        byte[] sign = SecureUtil.hmacSHA1(signData.getBytes(IUtil.DEFAULT_CHARSET),
                key.getBytes(IConst.DEFAULT_CHARSET));
        return ByteUtil.stdBase64Encode(sign);
    }

    @Test
    public void testGetUrlSignatureAndParams() {
        String obj = "a/b/c.txt";
        String ct = "text/plain";
        AbsOssClient.UrlRequest req = client.getUrl(obj, ct, null, null, 1024);

        //直链必须用外网接入点
        assertTrue(req.url.startsWith("https://" + OUTER + "/"));

        Map<String, String> q = parseQuery(req.url);
        assertTrue(q.containsKey("OSSAccessKeyId"), "should carry OSSAccessKeyId");
        assertTrue(q.containsKey("Expires"), "should carry Expires");
        assertTrue(q.containsKey("Signature"), "should carry Signature");
        assertEquals(KEY_ID, q.get("OSSAccessKeyId"));

        //重算签名，必须与 url 中的 Signature 完全一致
        int expires = Integer.parseInt(q.get("Expires"));
        String signData = "GET\n\n" + ct + "\n" + expires + "\n/" + BUCKET + "/" + obj;
        assertEquals(hmacB64(signData, KEY), UrlPathInfo.urlDecode(q.get("Signature")));

        //请求头
        assertEquals(ct, req.headers.get(HEAD_CONTENT_TYPE));
        assertEquals(ct, req.headers.get("response-content-type"));
        assertEquals(OUTER, req.headers.get(HEAD_HOST));
    }

    @Test
    public void testPutUrlSignatureAndParams() {
        String obj = "x/y.dat";
        String ct = "application/octet-stream";
        String md5 = "deadbeefcafe";
        AbsOssClient.UrlRequest req = client.putUrl(obj, ct, md5, null, 2048, 3600);

        assertTrue(req.url.startsWith("https://" + OUTER + "/"));
        Map<String, String> q = parseQuery(req.url);
        assertEquals(KEY_ID, q.get("OSSAccessKeyId"));
        int expiresAt = Integer.parseInt(q.get("Expires"));
        String signData = "PUT\n" + md5 + "\n" + ct + "\n" + expiresAt + "\n/" + BUCKET + "/" + obj;
        assertEquals(hmacB64(signData, KEY), UrlPathInfo.urlDecode(q.get("Signature")));
        assertEquals(OUTER, req.headers.get(HEAD_HOST));
    }

    @Test
    public void testPredictedHttpTime() {
        //小文件至少 300 秒超时
        assertEquals(300_000, AbsOssClient.predictedHttpTime(0));
        assertEquals(300_000, AbsOssClient.predictedHttpTime(60 * 1024));
        //大文件按 100 秒为阶递增
        long big = 300L * 100 * 60 * 1024; // size/60k = 30000 秒
        assertEquals(30_000_000, AbsOssClient.predictedHttpTime(big));
    }

    @Test
    public void testUrlRequestRoundTrip() {
        Map<String, String> h = new HashMap<>();
        h.put("k", "v");
        AbsOssClient.UrlRequest r = new AbsOssClient.UrlRequest("o", "m", "p",
                "http://x", h, 123L, 456);
        Map<String, Object> m = r.toMap();
        AbsOssClient.UrlRequest r2 = AbsOssClient.UrlRequest.fromMap(m);
        assertEquals("o", r2.objName);
        assertEquals("http://x", r2.url);
        assertEquals("m", r2.md5);
        assertEquals("p", r2.pwd);
        assertEquals(456, r2.size);
        assertEquals(h, r2.headers);
    }
}
