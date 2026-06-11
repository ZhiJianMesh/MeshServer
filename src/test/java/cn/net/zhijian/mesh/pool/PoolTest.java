package cn.net.zhijian.mesh.pool;

import static cn.net.zhijian.UnitTestBase.assertEquals;
import static cn.net.zhijian.UnitTestBase.assertTrue;
import static cn.net.zhijian.UnitTestBase.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public class PoolTest {
    private static class Resource extends AbsResource {
        private final int no;
        private boolean closed = false;
        
        public Resource(int no) {
            this.no = no;
        }
        
        @Override
        public void forceClose() {
            System.out.println("forceClose " + no);
            closed = true;
        }

        @Override
        public boolean test() {
            return !closed;
        }

        @Override
        public boolean closed() {
            return closed;
        }
    }
    
    private static class Factory implements IResourceFactory<Resource> {
        int no = 0;
        @Override
        public Resource create() {
            return new Resource(no++);
        }
    }
    
    @Test
    public void testResourcePool() throws Exception {
        int max = 10;
        try (ResourcePool<Resource> pool = new ResourcePool<>("test", max, 100, new Factory())) {
            Resource[] list = new Resource[max];
            for(int i = 0; i < max - 1; i++) {
                list[i] = pool.get();
                assertTrue(list[i] != null);
            }
            Resource r = pool.get();
            assertTrue(r != null);
            
            try {
                pool.get();
                fail("There should be an exception here");
            } catch(Exception e) {
            }
            
            r.close();
            r = pool.get();
            assertTrue(r != null);
            
            for(int i = 0; i < max - 1; i++) {
                pool.free(list[i]);
                list[i] = null;
            }
            
            for(int i = 0; i < max - 1; i++) {
                list[i] = pool.get();
                assertTrue(list[i] != null);
            }
        }
    }
    
    @Test
    public void testResPoolInMultiThread() throws Exception {
        int max = 50;
        long start = System.currentTimeMillis();
        CountDownLatch counter = new CountDownLatch(max);
        AtomicInteger err = new AtomicInteger(0);
        ResourcePool<Resource> pool = new ResourcePool<>("test", max, 500, new Factory());
        for(int i = 0; i < max; i++) {
            new Thread() {
                @Override
                public void run() {
                    try {
                        Resource r = (Resource)pool.get();
                        r.close();
                    } catch (Exception e) {
                        err.incrementAndGet();
                    } finally {
                        counter.countDown();
                    }
                }
            }.start();
        }

        counter.await();
        pool.close();
        long end = System.currentTimeMillis();
        long interval = end > start ? end - start : 1;
        System.out.println("Interval:" + interval + ",Speed:" + (1000L * max) / interval);
        assertEquals(0, err.get());
    }
}
