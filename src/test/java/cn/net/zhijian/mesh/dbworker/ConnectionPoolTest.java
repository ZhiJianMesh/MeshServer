package cn.net.zhijian.mesh.dbworker;

import java.io.Closeable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.frm.abs.AbsConnection;
import cn.net.zhijian.mesh.pool.IResourceFactory;
import cn.net.zhijian.mesh.pool.ResourcePool;

/**
 * 
 * @author flyinmind of csdn.net
 *
 */
public class ConnectionPoolTest extends UnitTestBase {
    private static final int N = 10;
    ResourcePool<AbsConnection> pool;
    ExecutorService works = Executors.newFixedThreadPool(N);
    static IResourceFactory<AbsConnection> factory = new IResourceFactory<AbsConnection>(){
        public TConnection create()  {
            return new TConnection(new RealConnection());
        }
    };

    static class RealConnection implements Closeable {
        @Override
        public void close() {
        }

        public boolean closed() {
            return false;
        }
    }

    static class TConnection extends AbsConnection {
        public TConnection(Closeable conn) {
            super(conn);
        }

        @Override
        public boolean closed() {
            return ((RealConnection)get()).closed();
        }

        @Override
        public boolean test() {
            return true;
        }
    }

    @Test
    public void testMultiGetConnectionNormal() throws Exception {
        try {
            pool = new ResourcePool<AbsConnection>("pool", N, 1000, factory);
            assertTrue(pool != null);
        } catch (MeshException e) {
            fail("Fail to create pool," + e.getLocalizedMessage());
        }
        CountDownLatch counter = new CountDownLatch(N);
        for(int i = 0; i < N; i++) {
            works.execute(() -> {
                try(AbsConnection c = pool.get()) {
                    if(c == null) {
                        fail("Fail to get connection from pool");
                    }
                    //System.out.println("pool left:" + pool.freeNum());
                } catch (MeshException e) {
                    fail("Fail to get connection from pool," + e.getLocalizedMessage());
                } finally {
                    counter.countDown();
                }
            });
        }
        counter.await();
        System.out.println("At last,pool left:" + pool.freeNum());
        assertEquals(pool.freeNum(), N);
    }

    @Test
    public void testMultiGetConnectionNoClose() throws Exception {
        try {
            pool = new ResourcePool<AbsConnection>("pool", N, 1000, factory);
            assertTrue(pool != null);
        } catch (MeshException e) {
            fail("Fail to create pool," + e.getLocalizedMessage());
        }

        CountDownLatch counter = new CountDownLatch(N);
        for(int i = 0; i < N; i++) {
            works.execute(() -> {
                try {
                    AbsConnection c = pool.get();
                    if(c == null) {
                        fail("Fail to get connection from pool");
                    }
                    //no close
                    //System.out.println("pool left:" + pool.freeNum());
                } catch (MeshException e) {
                    fail("Fail to get connection from pool," + e.getLocalizedMessage());
                } finally {
                    counter.countDown();
                }
            });
        }
        counter.await();
        AbsConnection c = null;
        try {
            c = pool.get();
        } catch(Exception e) {
        }
        assertTrue(c == null && pool.freeNum() == 0);
        pool.close();
    }

    @Test
    public void testMultiGetBusyConnectionPool() throws Exception {
        int poolSize = 5;
        int taskNum = 5000;
        long start = System.currentTimeMillis();
        try {
            pool = new ResourcePool<AbsConnection>("pool", poolSize, 5000, factory);
            assertTrue(pool != null);
        } catch (MeshException e) {
            fail("Fail to create pool," + e.getLocalizedMessage());
        }
        AtomicInteger num = new AtomicInteger(0);
        CountDownLatch counter = new CountDownLatch(taskNum);
        for(int i = 0; i < taskNum; i++) {
            works.execute(() -> {
                try (AbsConnection c = pool.get()){
                    if(c == null) {
                        fail("Fail to get connection from pool");
                    } else {
                        num.incrementAndGet();
                    }
                    //no close
                    //System.out.println("pool left:" + pool.freeNum());
                } catch (MeshException e) {
                    fail(e.getLocalizedMessage());
                } finally {
                    counter.countDown();
                }
            });
        }
        counter.await();
        try(AbsConnection c = pool.get()) {
            assertTrue(c != null);
        } catch(Exception e) {
            e.printStackTrace();
        }
        long end = System.currentTimeMillis();
        long interval = end - start;
        System.out.println("Get connection " + num.get()
        + " times, free num is " + pool.freeNum()
        + ",use time " + interval
        + "ms, each time uses " + (double)interval/taskNum + "ms");
        assertTrue(pool.freeNum() == poolSize && num.get() == taskNum);
        pool.close();
    }
}
