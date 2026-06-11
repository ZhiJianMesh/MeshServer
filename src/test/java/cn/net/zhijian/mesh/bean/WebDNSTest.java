package cn.net.zhijian.mesh.bean;

import org.junit.jupiter.api.Test;

import java.util.List;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.util.JsonUtil;

/**
 * 
 * @author flyinmind of csdn.net
 *
 */
public class WebDNSTest extends UnitTestBase {
    @Test
    public void testWebDNSBase() {
        String cfg = "[{addr:\"1.1.1.1:8080\",ver:\"0.0.1\","
                + "\"shardStart\":0,\"shardEnd\":100},"
                + "{addr:\"1.1.1.1:8080\",ver:\"0.0.1\","
                + "shardStart:100,shardEnd:32768}]";
        List<Object> list = JsonUtil.jsonToList(cfg);
        WebdbDNS dns = WebdbDNS.parse("testService", "testDb0", list);
        assertTrue(dns != null);

        /*分片范围太大*/
        cfg = "[{addr:\"1.1.1.1:8080\",ver:\"0.0.1\","
                + "shardStart:0,shardEnd:100},"
                + "{addr:\"1.1.1.1:8080\",ver:\"0.0.1\","
                + "shardStart:100,shardEnd:32769}]";
        list = JsonUtil.jsonToList(cfg);
        dns = WebdbDNS.parse("testService", "testDb1", list);
        assertTrue(dns == null);

        /*分片头部缺失*/
        cfg = "[{addr:\"1.1.1.1:8080\",ver:\"0.0.1\","
                + "shardStart:1,shardEnd:100},"
                + "{addr:\"1.1.1.1:8080\",ver:\"0.0.1\","
                + "shardStart:100,shardEnd:32768}]";
        list = JsonUtil.jsonToList(cfg);
        dns = WebdbDNS.parse("testService", "testDb2", list);
        assertTrue(dns == null);

        /*分片中部缺失*/
        cfg = "[{addr:\"1.1.1.1:8080\",ver:\"0.0.1\","
                + "shardStart:0,shardEnd:100},"
                + "{addr:\"1.1.1.1:8080\",ver:\"0.0.1\","
                + "shardStart:10,shardEnd:32768}]";
        list = JsonUtil.jsonToList(cfg);
        dns = WebdbDNS.parse("testService", "testDb3", list);
        assertTrue(dns == null);

        /* 分片尾部缺失 */
        cfg = "[{addr:\"1.1.1.1:8080\",ver:\"0.0.1\","
                + "shardStart:0,shardEnd:100},"
                + "{addr:\"1.1.1.1:8080\",ver:\"0.0.1\","
                + "shardStart:100,shardEnd:32767}]";
        list = JsonUtil.jsonToList(cfg);
        dns = WebdbDNS.parse("testService", "testDb4", list);
        assertTrue(dns == null);

        /*分片重叠*/
        cfg = "[{addr:\"1.1.1.1:8080\",ver:\"0.0.1\",shardStart:0,shardEnd:101},"
             + "{addr:\"1.1.1.1:8080\",ver:\"0.0.1\",shardStart:100,shardEnd:32768}]";
        list = JsonUtil.jsonToList(cfg);
        dns = WebdbDNS.parse("testService", "testDb6", list);
        assertTrue(dns == null);

        /*分片重叠，部分缺失*/
        cfg = "[{addr:\"1.1.1.1:8080\",ver:\"0.0.1\",shardStart:0,shardEnd:101},"
                + "{addr:\"1.1.1.1:8080\",ver:\"0.0.1\",shardStart:80,shardEnd:200},"
                + "{addr:\"1.1.1.1:8080\",ver:\"0.0.1\",shardStart:300,shardEnd:32768}]";
        list = JsonUtil.jsonToList(cfg);
        dns = WebdbDNS.parse("testService", "testDb6", list);
        assertTrue(dns == null);
    }

    @Test
    public void testWebDNSWithSlaves() {
        /*本地slave */
        String cfg = "[{addr:\"1.1.1.1:8080\",ver:\"0.0.1\",shardStart:0,shardEnd:100,\"slaves\":\"1.1.1.2:8080,1.1.1.3:8080\"},"
                + "{addr:\"1.1.1.1:8080\",ver:\"0.0.1\",shardStart:100,shardEnd:32768,\"status\":\"M\"},"
                + "{addr:\"1.1.1.2:8080\",ver:\"0.0.1\",shardStart:0,shardEnd:100,level:1,\"status\":\"S\"},"
                + "{addr:\"1.1.1.3:8080\",ver:\"0.0.1\",shardStart:0,shardEnd:100,level:1,\"status\":\"S\"}"
                + "]";
        List<Object> list = JsonUtil.jsonToList(cfg);
        WebdbDNS dns = WebdbDNS.parse("testService", "testDb1", list);
        assertTrue(dns != null && dns.shardings.length == 2);
        String[] slaves = dns.shardings[0].slaves();
        assertTrue(slaves.length == 2 && slaves[0].equals("1.1.1.2:8080"));
        NodeAddress node = dns.lookup(100, false, false, 1);
        assertTrue(node != null && node.level == 0 && node.addr.equals("1.1.1.1:8080"));
        node = dns.lookup(99, true, false, 1);
        assertEquals("1.1.1.1:8080", node.addr); //第一次读的仍然是主库
        node = dns.lookup(99, true, false, 1);
        assertEquals("1.1.1.2:8080", node.addr); //第二次轮到从库

        //转字符串后再次解析，如果仍然正常，则说明没问题
        //dns中已经删除了mode为slave的实例，所以slaves会被青储
        String s = dns.toString();
        list = JsonUtil.jsonToList(s);
        dns = WebdbDNS.parse("testService", "testDb1", list);
        assertTrue(dns != null && dns.shardings.length == 2);
        node = dns.lookup(100, false, false, 1);
        assertTrue(node != null && node.level == 0 && node.addr.equals("1.1.1.1:8080"));
    }
    
    @Test
    public void testWebDNSFaultSwitch() {
        /*本地slave */
        String cfg = "[{addr:\"1.1.1.1:8080\",ver:\"0.0.1\",shardStart:0,shardEnd:100},"
                + "{addr:\"1.1.1.2:8080\",ver:\"0.0.1\",shardStart:100,shardEnd:32768,level:0,\"slaves\":\"1.1.1.3:8080,1.1.1.4:8080\"},"
                + "{addr:\"1.1.1.3:8080\",ver:\"0.0.1\",shardStart:100,shardEnd:32768,level:1,\"status\":\"S\"},"
                + "{addr:\"1.1.1.4:8080\",ver:\"0.0.1\",shardStart:100,shardEnd:32768,level:1,\"status\":\"S\"}"
                + "]";
        List<Object> list = JsonUtil.jsonToList(cfg);
        WebdbDNS dns = WebdbDNS.parse("testService", "testDb1", list);
        assertTrue(dns != null);
        assertTrue(dns.shardings.length == 2);
        NodeAddress node = dns.lookup(100, false, false, 1);
        assertTrue(node != null);
        assertTrue(node.level == 0 && node.addr.equals("1.1.1.2:8080"));
        node.state = NodeAddress.ABNORMAL;
        node = dns.lookup(100, false, false, 1);
        assertTrue(node == null);
        node = dns.lookup(100, false, true, 1);
        assertTrue(node != null);
        assertTrue(node.level == 1 && node.addr.equals("1.1.1.3:8080"));
        node = dns.lookup(300, false, true, 1);
        assertTrue(node != null && node.level == 1 && node.addr.equals("1.1.1.3:8080"));
    }
    
    @Test
    public void testWebDNSWithInvalidSlaves() {
        /*本地slave */
        String cfg = "[{addr:\"1.1.1.1:8080\",ver:\"0.0.1\",shardStart:0,shardEnd:100,\"slaves\":\"1.1.1.2:8080,1.1.1.5:8080\"},"
                + "{addr:\"1.1.1.1:8080\",ver:\"0.0.1\",shardStart:100,shardEnd:32768,\"status\":\"M\",\"slaves\":\"1.1.1.4:8080,1.1.1.3:8080\"},"
                + "{addr:\"1.1.1.2:8080\",ver:\"0.0.1\",shardStart:0,shardEnd:100,level:1,\"status\":\"S\"},"
                + "{addr:\"1.1.1.3:8080\",ver:\"0.0.1\",shardStart:100,shardEnd:32768,level:1,\"status\":\"S\"},"
                + "{addr:\"1.1.1.5:8080\",ver:\"0.0.1\",shardStart:1,shardEnd:100,level:1,\"status\":\"S\"}"
                + "]";
        List<Object> list = JsonUtil.jsonToList(cfg);
        WebdbDNS dns = WebdbDNS.parse("testService", "testDb1", list);
        assertTrue(dns != null && dns.shardings.length == 2);
        String[] slaves = dns.shardings[0].slaves();
        assertTrue(slaves.length == 1 && slaves[0].equals("1.1.1.2:8080"));
        slaves = dns.shardings[1].slaves();
        assertTrue(slaves.length == 1 && slaves[0].equals("1.1.1.3:8080"));
    }
}
