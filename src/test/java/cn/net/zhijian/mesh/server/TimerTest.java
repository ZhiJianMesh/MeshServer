package cn.net.zhijian.mesh.server;

import static cn.net.zhijian.UnitTestBase.assertTrue;

import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.server.TimerKeeper.TimerTaskWrapper;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.DateUtil.PeriodType;

public class TimerTest {
    @Test
    public void testTaskWrapper() {
        long val = 1000;
        
        String name1 = "test1";
        TimerTaskWrapper tc1 = new TimerTaskWrapper(
            name1, PeriodType.CYCLE, val, () -> {});
        long cur = System.currentTimeMillis();
        tc1.updateNextTime(cur);
        long interval = tc1.nextTime() - cur;
        System.out.println("PERIOD)interval:" + interval + ",val:" + val);
        assertTrue(interval > 0 && interval <= val);
        
        Calendar cal = DateUtil.toUtcDate("2023-3-5 18:00:00");
        int hour1 = cal.get(Calendar.HOUR_OF_DAY);
        System.out.println("hour_of_day1:" + hour1 + ",utc date:" + cal.getTime());
        long end = cal.getTimeInMillis();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        int hour2 = cal.get(Calendar.HOUR_OF_DAY);
        val = end - cal.getTimeInMillis();
        System.out.println("hour_of_day2:" + hour2 + ",utc date:" + cal.getTime() + ",val:" + val);
        String name2 = "test2";
        TimerTaskWrapper tc2 = new TimerTaskWrapper(
            name2, PeriodType.DAY, val, () -> {});
        tc2.updateNextTime(System.currentTimeMillis());
        interval = tc2.nextTime() - cur;
        System.out.println("DAYRUNAT)interval:" + interval + ",val:" + val);
        assertTrue(val == (hour1-hour2)*3600000L && interval > 0 && interval <= 86400000L);
        
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        int day1 = Calendar.TUESDAY; //星期2
        cal.set(Calendar.DAY_OF_WEEK, day1);
        System.out.println("date1:" + cal.getTime());
        end = cal.getTimeInMillis();
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
        System.out.println("date2:" + cal.getTime());
        int day2 = cal.get(Calendar.DAY_OF_WEEK);
        val = (day1 - day2) * 86400000L;
        String name3 = "test3";
        TimerTaskWrapper tc3= new TimerTaskWrapper(
            name3, PeriodType.WEEK, val, () -> {});
        tc3.updateNextTime(System.currentTimeMillis());
        interval = tc3.nextTime() - cur;
        System.out.println("WEEKRUNAT)interval:" + interval + ",val:" + val + ",nextTime:" + new Date(tc3.nextTime()));
        assertTrue(val == (day1 - day2) * 86400000L
                && interval > 0 && interval < 7L * 86400000);
        
        cal = DateUtil.toUtcDate("2023-3-5 18:00:00");
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        System.out.println("date1:" + cal.getTime().toString());
        end = cal.getTimeInMillis();
        day1 = cal.get(Calendar.DAY_OF_MONTH);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        System.out.println("date2:" + cal.getTime().toString());
        val = end - cal.getTimeInMillis();
        day2 = cal.get(Calendar.DAY_OF_MONTH);
        String name4 = "test4";
        TimerTaskWrapper tc4 = new TimerTaskWrapper(
            name4, PeriodType.MONTH, val, () -> {});
        tc4.updateNextTime(System.currentTimeMillis());
        interval = tc4.nextTime() - cur;
        System.out.println("MONRUNAT)interval:" + interval + ",val:" + val + ",nextTime:" + new Date(tc3.nextTime()));
        assertTrue(val == (day1 - day2) * 86400000L
                && interval > 0
                && interval < 31L * 86400000);
    }
    
    @Test
    public void testTimerTask() {
        long val = 1000;
        CountDownLatch latch = new CountDownLatch(5);
        AtomicInteger counter = new AtomicInteger(0);
        
        String name1 = "test1"; //运行两次
        TimerKeeper.addTimerTask(new TimerTaskWrapper(
            name1, PeriodType.CYCLE, val,
            () -> {
                int v = counter.incrementAndGet();
                latch.countDown();
                System.out.println("task:" + name1 + ",counter:" + v);
            }
        ));
        
        Calendar cal = DateUtil.utcDate();
        long end = cal.getTimeInMillis() + 1000; //下一秒
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        val = end - cal.getTimeInMillis();
        String name2 = "test2"; //下一秒运行，只会运行一次
        TimerKeeper.addTimerTask(new TimerTaskWrapper(
            name2, PeriodType.DAY, val,
            () -> {
                int v = counter.incrementAndGet();
                latch.countDown();
                System.out.println("task:" + name2 + ",counter:" + v);
            }
        ));
        
        cal = DateUtil.utcDate();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
        val = end - cal.getTimeInMillis();
        String name3 = "test3"; //下一秒运行，只会运行一次
        TimerKeeper.addTimerTask(new TimerTaskWrapper(
            name3, PeriodType.WEEK, val,
            () -> {
                int v = counter.incrementAndGet();
                latch.countDown();
                System.out.println("task:" + name3 + ",counter:" + v);
            }
        ));

        cal = DateUtil.utcDate();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        val = end - cal.getTimeInMillis();
        String name4 = "test4"; //下一秒运行，只会运行一次
        TimerKeeper.addTimerTask(new TimerTaskWrapper(
            name4, PeriodType.MONTH, val,
            () -> {
                int v = counter.incrementAndGet();
                latch.countDown();
                System.out.println("task:" + name4 + ",counter:" + v);
            }
        ));
        TimerKeeper.startTimer();
        try {
            latch.await(2800, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
        }
        int c = counter.get();
        System.out.println("counter:" + c);
        assertTrue(c == 4 || c == 5); //定时器不是非常精确，CPU忙时会出现抖动
        TimerKeeper.destroy();
    }
}
