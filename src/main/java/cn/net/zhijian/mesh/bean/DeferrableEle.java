package cn.net.zhijian.mesh.bean;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可以定时更新的对象，如果更新失败，可以延长有效期，直到更新成功。
 * 每次更新都会提前一点点，更新期间，不可以再次更新，直到更新成功或失败。
 * 到了过期时间还没有更新，则延长两个租期，然后再次更新。
 * @author flyinmind of csdn.net
 *
 */
public class DeferrableEle extends ExpirableEle {
    private static final int DEFAULT_RENT_TIME = 10; //seconds
    private final int rentTime;
    protected final AtomicBoolean updating = new AtomicBoolean(false);

    public DeferrableEle(int expiresIn, int rentTime) {
        super(expiresIn);
        this.rentTime = rentTime * 1000;
    }
    
    public DeferrableEle(int expiresIn) {
        this(expiresIn, DEFAULT_RENT_TIME);
    }

    /**
     * 更新时间，做一个提前，部分防止在过期时，所有并发线程同时刷新。
     * 这种处理无法防止在刚启动时，多线程并发刷新
     * @return 是否需要更新，true为要更新
     */
    public boolean needUpdate() {
        if(expiresAt <= 0) { //始终不更新
            return false;
        }
        long cur = System.currentTimeMillis();
        if(!updating.get()) {
            if(cur >= expiresAt - rentTime) {
                updating.set(true);
                return true;
            }
        } else if(cur > expiresAt) { //长期刷新失败，自动延期
            updating.set(false);
            expiresAt += 2L * rentTime; //延期使用，等会重试，而不是立即重试
        }
        return false;
    }
    
    public void resetUpdate() {
        updating.set(false); //更新失败，需要重新更新
    }
}