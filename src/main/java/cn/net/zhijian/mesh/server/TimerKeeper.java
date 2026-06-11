package cn.net.zhijian.mesh.server;

import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.DateUtil.PeriodType;

public class TimerKeeper implements IThreadPool {
    private static final Logger LOG = LogUtil.getInstance();
    private static final int INTERVAL = 1000; //ms，每秒检查一次
    
    private static final List<TimerTaskWrapper> TimerTasks = new CopyOnWriteArrayList<>(); //适合读多写少的情况
    private static Timer PubTimer = null; //定时器是一个线程

    /**
     * 启动定时任务，每秒检查一次，如果任务已到期，则运行它。
     * 因为任务在pool线程池中执行，与服务执行是同一个pool，所以定时任务不宜耗时太长，
     * 或者将耗时的定时任务放在空闲时间段完成，比如定时备份，放在业务空闲时执行。
     * 此种实现，只能实现秒粒度的定时任务，
     * 好处是，定时任务可以随时添加或删除，且占用线程少。
     */
    public synchronized static void startTimer() {
        if(PubTimer == null) {
            PubTimer = new Timer("pub_timer");
        }
        PubTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                long cur = System.currentTimeMillis();
                for(TimerTaskWrapper tw : TimerTasks) {
                    if(cur >= tw.nextTime) {
                        Pool.execute(() -> { //任务放在ThreadPool中执行，避免堵塞Timer自身的线程
                            try { //捕捉全部异常，避免在线程池中发生异常，导致线程丢失
                                tw.task.run();
                            } catch(Throwable e) {
                                LOG.error("Fail to execute {}", tw.name, e);
                            }
                        });
                        tw.updateNextTime(cur);
                        LOG.debug("Execute timer task {},next {}", tw.name, new Date(tw.nextTime));
                    }
                }
            }
        }, INTERVAL, INTERVAL);
    }
    
    public static void destroy() {
        LOG.info("Timer destroy");
        PubTimer.cancel();
        PubTimer = null;
        TimerTasks.clear();
    }
    
    public static void addTimerTask(TimerTaskWrapper tc) {
        LOG.debug("Add timer task '{}', type:{},interval:{}", tc.name, tc.type.name(), tc.val);
        TimerTasks.add(tc);
    }
    
    public static void delayedTask(String name, int interval/*ms*/, Runnable task) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            try {
                LOG.info("Delayed task {} start", name);
                task.run();
                LOG.info("Delayed task {} over", name);
            } catch(Exception e) {
                LOG.error("Fail to execute {}", name, e);
            }
            scheduler.shutdown();
        }, interval, TimeUnit.MILLISECONDS);
    }
    
    public static void removeTimerTask(String name) {
        LOG.debug("Remove timer task {}", name);
        TimerTasks.removeIf(n -> n.name.equals(name));
    }
    
    public static class TimerTaskWrapper {
        private final String name;
        private final PeriodType type;
        /*
         * 离起点的相对时间值，type不同，值的含义也会不同
         * CYCLE:下一次运行的时间间隔，单位毫秒
         * DAY:一天中的时间点，单位毫秒，比如1:00，值为3600000
         * WEEK:一周中的时间点，单位毫秒，与周日0点的时间差
         * MONTH:一个月中的时间点，单位毫秒，与1号0点的时间差
         */
        private final long val;
        private final Runnable task;
        long nextTime;
        
        /**
         * 
         * @param name 名称
         * @param type 类型
         * @param val 周期或偏移值，单位毫秒，type为CYCLE时，表示间隔的毫秒数
         * @param task 定时任务
         */
        public TimerTaskWrapper(String name, PeriodType type, long val, Runnable task) {
            this.name = name;
            this.type = type;
            this.val = val;
            this.task = task;
            long next = System.currentTimeMillis();
            this.nextTime = DateUtil.recentNextTime(type, next, next, this.val);
        }
        
        public void updateNextTime(long cur) {
            this.nextTime = DateUtil.recentNextTime(this.type, cur, this.val);
        }
        
        public long nextTime() {
            return nextTime;
        }
    }
}
