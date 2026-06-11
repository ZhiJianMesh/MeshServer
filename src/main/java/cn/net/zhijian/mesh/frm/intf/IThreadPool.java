package cn.net.zhijian.mesh.frm.intf;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import cn.net.zhijian.mesh.js.JsEngine;

/**
 * 线程池&公共定时器，可以共用Pool与定时器
 * 避免创建过多的线程池与定时器
 * @author flyinmind of csdn.net
 *
 */
public interface IThreadPool {
    /**
     * 从DefaultThreadFactory拷贝过来，只是为了给线程一个自定义的名称
     * @author flyinmind of csdn.net
     */
    class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        public NamedThreadFactory(String name) {
            this.namePrefix = name + '-';
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(null, r, namePrefix + threadNumber.getAndIncrement(), 0);
            if (t.isDaemon()) {
                t.setDaemon(false);
            }
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }
    /*
     * 在一些环境下，如果使用newFixedThreadPool，会因为CPU太少，导致线程数太少。
     * 在启动期间，因为有大量的初始化访问，所以占用线程数太多，而平时访问量不大，
     * 所以如果newFixedThreadPool设置太大不合适，太小又满足不了启动期间的需求，
     * 所以不用newFixedThreadPool
     * 
     * 注意：SynchronousQueue是同步队列，没有存储空间，每个插入操作必须等待另一个线程的移除操作，
     * 性能很高，生产者和消费者速度相当时效果最佳。适合吞吐大，并发不高的场景。
     * Pool是高度公用的线程池，所以不能用SynchronousQueue，而用LinkedBlockingQueue
     */
    ExecutorService Pool = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() * 2, //如果用1，启动时就会吊死，未知原因
            Runtime.getRuntime().availableProcessors() * 5,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new NamedThreadFactory("process") {
                public Thread newThread(Runnable r) {
                    Runnable wrapper = () -> {
                        JsEngine.initContext(); //初始化ctx
                        try {
                            r.run();
                        } finally {
                            JsEngine.destroyContext(); //线程销毁时释放ctx
                        }
                    };
                    return super.newThread(wrapper);
                }
            });
}
