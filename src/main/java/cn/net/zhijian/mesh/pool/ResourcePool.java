package cn.net.zhijian.mesh.pool;

import java.io.Closeable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.util.LogUtil;

/**
 * 简易资源池实现，只支持固定大小的池子
 * 最初用于解决JDBC连接池在安卓与JVM中使用导致两边代码差异过大的问题，
 * 如果使用开源连接池，可能会引入依赖包，导致安卓不兼容的问题
 * 后面抽象成一个统一的资源池管理类，比如管理JSContext资源池
 * @author flyinmind of csdn.net
 *
 */
public class ResourcePool<T extends AbsResource> implements Closeable {
    private static final Logger LOG = LogUtil.getInstance();
    
    private final ConcurrentLinkedDeque<T> resources = new ConcurrentLinkedDeque<>();
    private final Semaphore semaphore;
    private final int timeout;
    private final String name;
    private final IResourceFactory<T> factory;
    private final CountDownLatch closeCounter; //用于关闭时等待资源回收
    
    private volatile boolean working = true;

    /**
     * 
     * @param name 资源池名称
     * @param poolSize 资源池大小，至少为1
     * @param timeout 获取资源时，最大等待时间，单位毫秒
     * @param factory 资源工厂
     */
    public ResourcePool(String name, int poolSize, int timeout, IResourceFactory<T> factory) throws MeshException {
        if (poolSize < 1) {
            throw new IllegalArgumentException("Invalid poolSize, must be bigger than 1");
        }
        this.closeCounter = new CountDownLatch(poolSize);
        if(LOG.isDebugEnabled()) {
            LOG.debug("Create ResourcePool {}, poolSize:{}", name, poolSize);
        }
        this.factory = factory;
        for(int i = 0; i < poolSize; i++) { //提前建好连接
            T res = factory.create();
            res.setPool(this);
            this.resources.add(res);
        }
        this.semaphore = new Semaphore(poolSize, true);
        this.timeout = timeout;
        this.name = name + "(max:" + poolSize + ",timeout:" + timeout + ')';
    }
    
    public T get() throws MeshException {
        if(!working) {
            throw new MeshException("Pool `" + name + "` is closed");
        }

        try {
            if(!semaphore.tryAcquire(timeout, TimeUnit.MILLISECONDS)) {
                throw new MeshException("No free resource in pool "
                              + name + ",left " + resources.size());
            }
        } catch (InterruptedException e) {
            throw new MeshException("Interrupted while waiting for a resource in the pool `" + name + '`', e);
        }
        
        T res = resources.pop();
        if(res != null && res.test()) { //如果测试成功，直接返回，否则需要重新创建资源
            return res;
        }
        
        res = factory.create();
        res.setPool(this);
        return res;
    }
    
    public int freeNum() {
        return resources.size();
    }
    
    public void free(T res) {
        if(working) {
            resources.add(res);
            semaphore.release();
        } else { //资源池已经关闭，但是还有资源没有释放的情况下，会走到这里
            LOG.debug("Force to close resource {}", name);
            res.forceClose();
            closeCounter.countDown();
        }
    }

    @Override
    public synchronized void close() {
        if(!working) {
            return;
        }
        LOG.debug("Close ResourcePool {}, current size:{}", name, resources.size());

        working = false;
        for(AbsResource res : resources) { //只能关闭处于空闲状态的链接
            res.forceClose();
            closeCounter.countDown();
        }
        resources.clear();

        try {
            /*
             * 等待其他连接都关闭，最多等3秒
             * 使用sqlite，关闭时会调用vacuum，如果此处连接未全部关闭，
             * 就可能会出现两个写连接，导致数据库锁住或损坏
             */
            closeCounter.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
        }
    }
}