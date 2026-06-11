package cn.net.zhijian.mesh.frm.config.aclchecker;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class AclCheckerTest extends UnitTestBase {
    @Test
    public void testMultiThreadLoading() throws InterruptedException {
        AtomicInteger n = new AtomicInteger(0);
        String[] checkers = new String[] {"RBAC"};
        int N = 3;
        CountDownLatch counter = new CountDownLatch(N);
        for (int i = 0; i < N; i++) {
            new Thread(() -> {
                for(String c : checkers) {
                    AclChecker ac = AclChecker.getChecker(c, null);
                    assertTrue(ac != null);
                    n.incrementAndGet();
                }
                counter.countDown();
            }).start();
        }
        counter.await();
        assertEquals(n.get(), N * checkers.length);
    }
}
