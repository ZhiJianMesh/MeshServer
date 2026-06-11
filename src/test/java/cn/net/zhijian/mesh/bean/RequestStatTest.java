package cn.net.zhijian.mesh.bean;

import org.junit.jupiter.api.Test;
import cn.net.zhijian.UnitTestBase;

public class RequestStatTest extends UnitTestBase {
    @Test
    public void statTest() {
        RequestStat rs = new RequestStat();
        rs.incApis(2);
        rs.incExceptions(1);
        rs.incFailures(1);
        assertEquals(rs.incApis(0), 2);
        assertEquals(rs.incExceptions(0), 1);
        assertEquals(rs.incFailures(0), 1);
        
        int v = rs.apis();
        assertEquals(v, 2);
        rs.incApis(1);
        v = rs.apis();
        assertEquals(v, 1);
        v = rs.apis();
        assertEquals(v, 0);
        
        v = rs.exceptions();
        assertEquals(v, 1);
        rs.incExceptions(1);
        v = rs.exceptions();
        assertEquals(v, 1);
        v = rs.exceptions();
        assertEquals(v, 0);
        
        v = rs.failures();
        assertEquals(v, 1);
        rs.incFailures(1);
        v = rs.failures();
        assertEquals(v, 1);
        v = rs.failures();
        assertEquals(v, 0);
    }
}
