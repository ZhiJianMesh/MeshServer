package cn.net.zhijian.mesh.bean;

import static cn.net.zhijian.UnitTestBase.assertTrue;

import org.junit.jupiter.api.Test;

public class SimpleBeanTest {
    @Test
    public void testNodeAddr() {
        NodeAddress na = new NodeAddress("test", "1.1.1.1", 1000, 1);
        na.state = NodeAddress.ABNORMAL;
        na.reset();
        assertTrue(na.normal());
    }
    
    @Test
    public void testServerState() {
        long cur = System.currentTimeMillis();
        ServerState ss = new ServerState();
        assertTrue(!ss.isRunning());
        assertTrue(ServerState.isRunning(ServerState.RUNNING));
        ss.state(ServerState.RUNNING);
        assertTrue(ss.isRunning());
        ss.startupTime(cur + 100); //超短时间内设置，不会改变原来的启动时间记录
        assertTrue(ss.startupTime() - cur < 100);
    }
    
    @Test
    public void testExpirableEle() throws InterruptedException {
        ExpirableEle ee = new ExpirableEle(1);
        Thread.sleep(1500);
        assertTrue(ee.expired());
    }
    
    @Test
    public void testUpdatableEle() throws InterruptedException {
        DeferrableEle ue = new DeferrableEle(2, 1);
        assertTrue(!ue.expired());
        assertTrue(!ue.needUpdate());
        Thread.sleep(1100);
        assertTrue(!ue.expired());
        assertTrue(ue.needUpdate());
        assertTrue(!ue.needUpdate());
        Thread.sleep(1100);
        assertTrue(!ue.needUpdate()); //自动延期使用
        assertTrue(!ue.expired());
    }    
}
