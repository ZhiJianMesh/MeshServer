package cn.net.zhijian.mesh.client;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.util.HttpUtil;

import java.util.concurrent.TimeUnit;

public class HttpClientTest extends UnitTestBase {
    @Test
    public void testGetExternalBinary() {
        try {
            byte[] c = HttpClient.getExternalBinary("https://www.baidu.com",
                    null).get(10, TimeUnit.SECONDS);
            assertTrue(c != null && c.length > 0);
        } catch (Exception e) {
            fail(e.getLocalizedMessage());
        }
    }
    
    @Test
    public void testHttpClientGetString()  {
        try {
            String s = HttpClient.disguiserGet("https://www.baidu.com", null).get(10, TimeUnit.SECONDS);
            assertTrue(s != null && s.startsWith("<!DOCTYPE html>"));
        } catch (Exception e) {
            fail(e.getLocalizedMessage());
        }
    }
    
    @Test
    public void testServiceUrl() {
        String api = HttpClient.serviceApiUrl("https://", "test", "/a/get", "api.zhijian.net.cn:8523");
        assertEquals(api, "https://api.zhijian.net.cn:8523/test/api/a/get");
        String file = HttpClient.serviceFileUrl("http://", "test", "/a/b.txt", "api.zhijian.net.cn:8523");
        assertEquals(file, "http://api.zhijian.net.cn:8523/test/a/b.txt");
    }
    
    @Test
    public void testHttpClients() {
        int N = 5;
        int I = 10 * 1000;
        int timeout = I;
        for(int i = 0; i < N; i++) {
            HttpClient.getHttpClient(timeout);
            timeout += 10;
        }
        HttpUtil c = HttpClient.getHttpClient(I + 1);
        assertEquals(c.timeout, I + 10);
        c = HttpClient.getHttpClient(I + N * 10);
        assertEquals(c.timeout, I + N * 10);
    }
}
