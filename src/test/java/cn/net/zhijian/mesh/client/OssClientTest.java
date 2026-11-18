package cn.net.zhijian.mesh.client;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;

/**
 * AliOssClient 单元测试。
 * 覆盖 getUrl/putUrl 的 HMAC-SHA1 签名、直链 URL 参数、请求头构造，
 * 以及 AbsOssClient.predictedHttpTime / UrlRequest 序列化——均为纯逻辑，不发起网络。
 */
public class OssClientTest extends UnitTestBase {
    @Test
    public void testPredictedHttpTime() {
        //小文件至少 300 秒超时
        assertEquals(300_000, OssClient.predictedHttpTime(0));
        assertEquals(300_000, OssClient.predictedHttpTime(60 * 1024));
        //大文件按 100 秒为阶递增
        long big = 300L * 100 * 60 * 1024; // size/60k = 30000 秒
        assertEquals(30_000_000, OssClient.predictedHttpTime(big));
    }

    @Test
    public void testUrlRequestRoundTrip() {
        Map<String, String> h = new HashMap<>();
        h.put("k", "v");
        OssClient.UrlRequest r = new OssClient.UrlRequest("o", "m", "p",
                "http://x", h, 123L, 456);
        Map<String, Object> m = r.toMap();
        OssClient.UrlRequest r2 = OssClient.UrlRequest.fromMap(m);
        assertEquals("o", r2.objName);
        assertEquals("http://x", r2.url);
        assertEquals("m", r2.md5);
        assertEquals("p", r2.pwd);
        assertEquals(456, r2.size);
        assertEquals(h, r2.headers);
    }

    @Test
    public void testMultipartPartSize() {
        int ps16 = OssClient.partSize(16L * 1024 * 1024);
        assertEquals(1024 * 1024, ps16); //16M按1MB切片
        assertEquals(16, (int)((16L * 1024 * 1024 + ps16 - 1) / ps16));

        assertEquals(1024 * 1024, OssClient.partSize(0)); //小文件也保持1MB
        assertEquals(1024 * 1024, OssClient.partSize(3L * 1024 * 1024));

        long huge = 100L * 1024 * 1024 * 1024; //100G，片数不能超过OSS的1万片上限
        int ps = OssClient.partSize(huge);
        assertTrue(ps >= 1024 * 1024);
        assertTrue((huge + ps - 1) / ps <= 10000);
    }

    @Test
    public void testMultipartObjectName() {
        assertEquals("B0A1.z01", OssClient.objectNameOf(
                "https://zjhzuserdb.oss-cn-hangzhou.aliyuncs.com/B0A1.z01?OSSAccessKeyId=k&Expires=1&Signature=s"));
        assertEquals("a/b/c.txt", OssClient.objectNameOf("https://h/a/b/c.txt"));
        assertNull(OssClient.objectNameOf(""));
        assertNull(OssClient.objectNameOf("not a url"));
    }

    @Test
    public void testMultipartUploadIdParsing() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<InitiateMultipartUploadResult><Bucket>b</Bucket>"
                + "<Key>k</Key><UploadId>ABC123</UploadId></InitiateMultipartUploadResult>";
        assertEquals("ABC123", OssClient.tag(xml, "UploadId"));
        assertNull(OssClient.tag(xml, "NotExists"));
        assertNull(OssClient.tag(null, "UploadId"));
    }
}
