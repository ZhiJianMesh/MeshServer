package cn.net.zhijian.mesh.prot.http;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;

/**
 * Http1ServerRequest.isFromLan 静态纯逻辑单元测试（不依赖 Netty Channel）。
 * 覆盖：环回地址、站点本地地址、公网源、经代理头带公网/内网地址四种场景。
 */
public class HttpServerRequestTest extends UnitTestBase {

    private static InetSocketAddress addr(String ip) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(ip), 0);
    }

    @Test
    public void testLoopbackIsLan() throws Exception {
        assertTrue(Http1ServerRequest.isFromLan(addr("127.0.0.1"), Collections.emptyMap()));
    }

    @Test
    public void testSiteLocalIsLanWithoutProxy() throws Exception {
        assertTrue(Http1ServerRequest.isFromLan(addr("192.168.1.5"), Collections.emptyMap()));
        assertTrue(Http1ServerRequest.isFromLan(addr("10.0.0.5"), Collections.emptyMap()));
    }

    @Test
    public void testPublicSourceIsWan() throws Exception {
        assertFalse(Http1ServerRequest.isFromLan(addr("8.8.8.8"), Collections.emptyMap()));
    }

    @Test
    public void testPublicXForwardMeansWan() throws Exception {
        //源是内网，但代理头里出现公网地址，说明经过公网代理，应判定为外网
        Map<String, String> h = new HashMap<>();
        h.put("x-forwarded-for", "9.9.9.9");
        assertFalse(Http1ServerRequest.isFromLan(addr("10.0.0.5"), h));
    }

    @Test
    public void testAllLanXForwardKeepsLanWhenSourceLan() throws Exception {
        //代理头全部为内网地址且源也是内网，仍为内网
        Map<String, String> h = new HashMap<>();
        h.put("x-forwarded-for", "192.168.1.1, 10.0.0.1");
        assertTrue(Http1ServerRequest.isFromLan(addr("192.168.1.5"), h));
    }
}
