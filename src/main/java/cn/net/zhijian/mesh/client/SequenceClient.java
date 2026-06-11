package cn.net.zhijian.mesh.client;

import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 序列号ID客户端，
 * 一次向数据库申请一段，用完重新申请，依赖数据库的事务性实现分段不重叠。
 * 不能保证连续递增，但是可以保证持续增长，不重复。
 * @author flyinmind of csdn.net
 *
 */
public final class SequenceClient extends ServiceClient {
    private static final Logger LOG = LogUtil.getInstance();
    //记录不多，且无需过期，所以使用map替代缓存
    private static final Map<Long, UsableID> SeqIdCache = new ConcurrentHashMap<>();

    public static long nextId(int cid, ServiceInfo caller, String name, String traceId) {
        long nextId;
        long cacheId = StringUtil.longHashCode(cid, "-", caller.name, "-", name);
        UsableID newId = new UsableID();
        UsableID oldId = SeqIdCache.putIfAbsent(cacheId, newId);
        UsableID usableId = oldId == null ? newId : oldId; //如果无老的，则使用新建的

        /* 
         * 一个线程请求就可以了，其他线程等待。
         * 因为ConcurrentHashMap.putIfAbsent能够保证原子性，
         * 如果开始不存在，当下一个线程到达时，获取的一定是本线程新建的newId，
         */
        usableId.lock.lock();
        try {
            if((nextId = usableId.nextLong()) == Long.MIN_VALUE) {
                UrlPathInfo url = new UrlPathInfo("get").appendPara("name", name, true);
                ServiceReqBuilder builder = new ServiceReqBuilder(caller, SERVICE_SEQID)
                        .url(url.toString()).appToken("*")
                        .traceId(traceId).cid(cid);
                try {
                    HandleResult hr = getPublic(builder).get(5, TimeUnit.SECONDS);
                    if(hr == null || hr.code != RetCode.OK || hr.data == null) {
                        LOG.error("Fail to get next long id {} of {}.{}, result:{}",
                                cacheId, caller.name, name, (hr != null ? hr.brief() : "null"));
                        return Long.MIN_VALUE;
                    }
    
                    long cur = ValParser.getAsLong(hr.data, "cur");
                    long end = ValParser.getAsLong(hr.data, "end");
                    usableId.set(cur, end);
    
                    nextId = usableId.nextLong();
                } catch (Exception e) {
                    LOG.error("Fail to get sequence id of {}.{}", caller.name, name, e);
                }
            }
        } finally {
            usableId.lock.unlock();
        }

        return nextId;
    }

    public static int nextIntId(int cid, ServiceInfo caller, String name, String traceId) {
        long lv = nextId(cid, caller, name, traceId);
        return (int)(lv % Integer.MAX_VALUE); //防止出现负值
    }
    
    public static CompletableFuture<Boolean> init(ServiceInfo caller, int cid, String name, int begin) {
        ServiceReqBuilder builder = new ServiceReqBuilder(caller, SERVICE_SEQID)
                .url("/init")
                .appendPara("name", name)
                .appendPara("begin", Integer.toString(begin))
                .traceId(caller.name + '_' + name)
                .appToken("*")
                .cid(cid);
        return getPublic(builder).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to init {}.{},begin:{},result:{}",
                        caller.name, name, begin, hr.brief());
                return false;
            }
            return true;
        }, Pool).exceptionally(e ->{
            LOG.error("Fail to init {}.{},begin:{}", caller.name, name, begin);
            return false;
        });
    }

    private static class UsableID {
        private volatile long end = Long.MIN_VALUE;
        private volatile long cur;
        private final ReentrantLock lock = new ReentrantLock();

        public UsableID() {
            set(Long.MIN_VALUE, Long.MIN_VALUE);
        }

        public long nextLong() {
            if(this.cur < end) {
                long v = this.cur;
                this.cur=v+1; //消除spotbugs错误，不能用this.cur++
                return v;
            }
            return Long.MIN_VALUE;
        }

        void set(long cur, long end) {
            this.cur = cur;
            this.end = end;
        }
    }
}

