package cn.net.zhijian.mesh.bean;

import static cn.net.zhijian.UnitTestBase.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 
 * @author flyinmind of csdn.net
 *
 */
public class ServiceDNSTest {
    @Test
    public void testServiceDNS() {
        ServiceDNS dns = ServiceDNS.create("test", new String[] {"1.1.1.1:8080", "2.1.2.1:8080", "33.45.6.78:8080"}, 10000);
        NodeAddress na = dns.lookup("test".hashCode());
        assertTrue(na != null);
    }
}
