package cn.net.zhijian.mesh.server;

import static cn.net.zhijian.UnitTestBase.assertTrue;

import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.server.TimerKeeper.TimerTaskWrapper;
import cn.net.zhijian.util.DateUtil.PeriodType;

public class TimerKeeperTest {
    private static final String TIMER_NAME = "test";
    
    @BeforeAll
    public static void init() {
        TimerKeeper.startTimer();
    }
    
    @AfterAll
    public static void over() {
        TimerKeeper.destroy();
    }
    
    @Test
    public void testCycle() throws Exception {
        long start = System.currentTimeMillis();
        int waitTimes = 5;
        CountDownLatch counter = new CountDownLatch(waitTimes);
        int interval = 1000;
        TimerKeeper.addTimerTask(new TimerTaskWrapper(TIMER_NAME, PeriodType.CYCLE, interval, () -> {
            counter.countDown();
        }));
        counter.await();
        long end = System.currentTimeMillis();
        long last = end - start;
        System.out.println("used time:" + last);
        assertTrue(last >= waitTimes * interval && last < (waitTimes + 2) * interval);
        TimerKeeper.removeTimerTask(TIMER_NAME);
    }
    
    @Test
    public void testHour() throws Exception {
        long start = System.currentTimeMillis();
        int waitTimes = 1;
        CountDownLatch counter = new CountDownLatch(waitTimes);
        int interval = 1000;
        long v = (start % 3600000) + interval; //下一秒运行
        TimerKeeper.addTimerTask(new TimerTaskWrapper(TIMER_NAME, PeriodType.HOUR, v, () -> {
            counter.countDown();
        }));
        
        counter.await();
        long end = System.currentTimeMillis();
        long last = end - start;
        System.out.println("used time:" + last);
        assertTrue(last >= waitTimes * interval && last < (waitTimes + 2) * interval);
        TimerKeeper.removeTimerTask(TIMER_NAME);
    }
    
    @Test
    public void testDay() throws Exception {
        long start = System.currentTimeMillis();
        int waitTimes = 1;
        CountDownLatch counter = new CountDownLatch(waitTimes);
        int interval = 1000;
        long v = (start % 86400000) + interval; //下一秒运行
        TimerKeeper.addTimerTask(new TimerTaskWrapper(TIMER_NAME, PeriodType.DAY, v, () -> {
            counter.countDown();
        }));
        
        counter.await();
        long end = System.currentTimeMillis();
        long last = end - start;
        System.out.println("used time:" + last);
        assertTrue(last >= waitTimes * interval && last < (waitTimes + 2) * interval);
        TimerKeeper.removeTimerTask(TIMER_NAME);
    }
}
