package cn.net.zhijian.mesh.pool;

/**
 * 资源
 */
public abstract class AbsResource implements AutoCloseable {
    private ResourcePool<AbsResource> pool = null;
    
    /**
     * 强迫关闭连接，无论释放在资源池中
     * 与close不同，如果在资源池中，调用close只是将资源归还给资源池，
     * 不在资源池中，调用close时调用forceClose
     */
    public abstract void forceClose();
    /**
     * 检查资源是否正常，实现时可以采用懒检查的方式，
     * 只有被调用，且过了一定周期的情况下才调用
     * @return 如果正常，则返回true
     */
    public abstract  boolean test();

    /**
     * 判断是否关闭了
     * @return 是否关闭了
     */
    public abstract boolean closed();
    
    @SuppressWarnings("unchecked")
    public <T extends AbsResource> void setPool(ResourcePool<T> pool) {
        this.pool = (ResourcePool<AbsResource>)pool;
    }
    
    @Override
    public final void close() {
        if(pool == null) { //不在资源池中，直接关闭
            forceClose();
        } else {
            pool.free(this); //必须还给资源池
        }
    }
}
