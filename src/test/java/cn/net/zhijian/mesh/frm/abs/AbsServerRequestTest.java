package cn.net.zhijian.mesh.frm.abs;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.prot.http.Http1ServerRequest;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

public class AbsServerRequestTest extends UnitTestBase {
    @Test
    public void testIPv6IsFromLan() {
        InetSocketAddress addr = new InetSocketAddress("0:0:0:0:0:0:0:1", 8080);
        Map<String, String> headers = new HashMap<>();
        headers.put("x-forwarded-for", "181.1.1.1");
        assertTrue(Http1ServerRequest.isFromLan(addr, headers));
        
        addr = new InetSocketAddress("fe80::3943:28bb:6a80:d0ac", 8080);
        headers = new HashMap<>();
        headers.put("x-forwarded-for", "10.1.1.1");
        assertTrue(Http1ServerRequest.isFromLan(addr, headers));
        
        addr = new InetSocketAddress("fe80::3943:28bb:6a80:d0ac", 8080);
        headers = new HashMap<>();
        headers.put("x-forwarded-for", "10.1.1.1;ac80::3943:28bb:6a80:d0ac");
        assertTrue(!Http1ServerRequest.isFromLan(addr, headers));
        
        addr = new InetSocketAddress("ae80::3943:28bb:6a80:d0ac", 8080);
        headers = new HashMap<>();
        headers.put("x-forwarded-for", "10.1.1.1;ac80::3943:28bb:6a80:d0ac");
        assertTrue(!Http1ServerRequest.isFromLan(addr, headers));
        headers.put("x-forwarded-for", "10.1.1.1;181.1.1.1");
        assertTrue(!Http1ServerRequest.isFromLan(addr, headers));
    }

    @Test
    public void testIPv4IsFromLan() {
        InetSocketAddress addr = new InetSocketAddress("192.168.0.1", 8080);
        Map<String, String> headers = new HashMap<>();
        headers.put("x-forwarded-for", "10.1.1.1;180.10.10.10");
        assertTrue(!Http1ServerRequest.isFromLan(addr, headers));
        
        addr = new InetSocketAddress("127.0.0.1", 8080);
        headers = new HashMap<>();
        headers.put("x-forwarded-for", "10.1.1.1");
        assertTrue(Http1ServerRequest.isFromLan(addr, headers));
        
        addr = new InetSocketAddress("172.16.20.22", 8080);
        headers = new HashMap<>();
        headers.put("x-forwarded-for", "10.1.1.1");
        assertTrue(Http1ServerRequest.isFromLan(addr, headers));
        addr = new InetSocketAddress("172.16.20.22", 8080);
        
        headers = new HashMap<>();
        headers.put("x-real-ip", "10.1.1.1;192.168.0.1;193.11.11.1");
        assertTrue(!Http1ServerRequest.isFromLan(addr, headers));
        
        
        addr = new InetSocketAddress("188.16.20.22", 8080);
        headers = new HashMap<>();
        headers.put("x-forwarded-for", "10.1.1.1");
        assertTrue(!Http1ServerRequest.isFromLan(addr, headers));
    }
    
    @Test
    public void testTraceNoPos() {
        String traceId = "mc_adr_c00#123";
        int pos = AbsServerRequest.traceNoPos(traceId);
        assertEquals(pos, 10);
        int no = Integer.parseInt(traceId.substring(pos + 1));
        assertEquals(no, 123);
        traceId = "mc_adr_c00";
        pos = AbsServerRequest.traceNoPos(traceId);
        assertEquals(pos, -1);
        traceId = "mc_adr_#c00";
        pos = AbsServerRequest.traceNoPos(traceId);
        assertEquals(pos, -1);
        traceId = "mc_adr_c00#";
        pos = AbsServerRequest.traceNoPos(traceId);
        assertEquals(pos, -1);
    }
}
