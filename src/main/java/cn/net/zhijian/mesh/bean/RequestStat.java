package cn.net.zhijian.mesh.bean;

import java.util.concurrent.atomic.AtomicLong;

public class RequestStat {
    private final AtomicLong apis = new AtomicLong(0); //接口访问次数
    private final AtomicLong failures = new AtomicLong(0); //接口访问失败次数
    private final AtomicLong exceptions = new AtomicLong(0); //接口访问异常次数
    
    //获取访问增量时，需要与以下的快照计算差值
    private long apisSnapshot = 0;
    private long failsSnapshot = 0;
    private long exceptionsSnapshot = 0;
    //private long filesSnapshot = 0;
    
    public long incApis(int v) {
        return apis.addAndGet(v);
    }
    
    public long incFailures(int v) {
        return failures.addAndGet(v);
    }
    
    public long incExceptions(int v) {
        return exceptions.addAndGet(v);
    }

    public int apis() {
        long cur = apis.get();
        int v = (int)(cur - apisSnapshot);
        apisSnapshot = cur;
        return v;
    }
    
    public int exceptions() {
        long cur = exceptions.get();
        int v = (int)(cur - exceptionsSnapshot);
        exceptionsSnapshot = cur;
        return v;
    }
    
    public int failures() {
        long cur = failures.get();
        int v = (int)(cur - failsSnapshot);
        failsSnapshot = cur;
        return v;
    }
}
