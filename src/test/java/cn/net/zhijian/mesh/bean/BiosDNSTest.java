package cn.net.zhijian.mesh.bean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.util.MapBuilder;

public class BiosDNSTest extends UnitTestBase {
    @Test
    public void testBiosDns() {
        String main = "192.168.0.1:8525";
        List<String> addrs = new ArrayList<>();
        addrs.add("192.168.0.1:8523");
        addrs.add(main);
        addrs.add("192.168.0.6:8523");
        BiosDNS dns = null;
        try {
            dns = new BiosDNS(addrs, main);
            assertEquals(dns.mainAddr(), main);
            NodeAddress[] slaves = dns.slaves(main);
            assertEquals(slaves.length, 2);
            assertEquals(slaves[0].addr, "192.168.0.1:8523");
        } catch (MeshException e) {
            fail(e.getMessage());
        }
        
        if(dns == null) { //消除eclipse null检查的报警
            fail("Invalid dns");
            return;
        }
        
        dns.setMain("192.168.0.1:8525");
        assertEquals(dns.mainAddr(), "192.168.0.1:8525");
        dns.setMain("192.168.0.8:8525");
        assertEquals(dns.mainAddr(), "192.168.0.1:8525");
        
        addrs.add("192.168.0.8:8523");
        try {
            dns.refresh(addrs, main);
        } catch (MeshException e) {
            fail(e.getMessage());
        }
        assertEquals(dns.mainAddr(), "192.168.0.1:8525");
        
        assertTrue(dns.isBios("192.168.0.1:8525"));
        
        addrs.clear();
        addrs.add("192.168.0.8:8523");
        addrs.add("192.168.0.1:8523");
        addrs.add("192.168.0.1:8525");
        addrs.add("192.168.0.6:8523");
        try {
            dns.refresh(addrs, main);
        } catch (MeshException e) {
            fail(e.getMessage());
        }
        try {
            dns.refresh(addrs, "192.168.1.1:8523");
            fail("fail to judge main");
        } catch (MeshException e) {
        }
        assertEquals(dns.mainAddr(), "192.168.0.1:8525");
    }
    
    @Test
    public void testResponse() {
        String main = "192.168.0.1:8525";
        List<String> addrs = new ArrayList<>();
        addrs.add("192.168.0.1:8523");
        addrs.add(main);
        addrs.add("192.168.0.6:8523");
        try {
            BiosDNS dns = new BiosDNS(addrs, main);
            addrs.add("192.168.0.8:8523");
            Map<String, Object> data = MapBuilder.of("main", "192.168.0.8:8523", "list", addrs);
            dns.handleResp(dns.mainNode(), data);

            assertEquals(dns.slaves(main).length, 3);
            assertEquals(dns.mainAddr(), "192.168.0.8:8523");
        } catch (MeshException e) {
            fail(e.getMessage());
        }

    }
}
