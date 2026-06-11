package cn.net.zhijian;

import java.io.File;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.util.FileUtil;

public class BaseTest extends UnitTestBase {
    @Test
    public void testRegular() { //用于测试一些常用的正则判断
        String regular = "^[^()\"']+$";

        assertFalse("0,(".matches(regular));
        assertFalse("0,)".matches(regular));
        assertFalse("0,\"".matches(regular));
        assertFalse("0,',3".matches(regular));
        assertTrue("0,3,and,5".matches(regular));
//          String s = "aaabccegaafe";
//          System.out.println(s.replace("aa", "GG"));
//          long lv = 509999999999L;
//          int iv = (int)lv;
//          System.out.println("iv1:" + iv);
//          iv = (int)(lv % Integer.MAX_VALUE);
//          System.out.println("iv2:" + iv);

        Pattern pattern = Pattern.compile("[\\w|_!@#$%^&*]{4,20}");
        assertTrue(pattern.matcher("abc_@").matches());
        assertTrue(pattern.matcher("000abc_@000").matches());
        assertTrue(pattern.matcher("abc|_@dd^d").matches());
        assertTrue(pattern.matcher("ab!c4_@").matches());
    }

    @Test
    public void testAtomicIntegerInc() {
        AtomicInteger c = new AtomicInteger(Integer.MAX_VALUE);
        int v = c.incrementAndGet();
        assertEquals(v, 1 << 31);
        assertTrue(v < 0);
//        String s = "aaabccegaafe";
//        System.out.println(s.replace("aa", "GG"));
//        long lv = 509999999999L;
//        int iv = (int)lv;
//        System.out.println("iv1:" + iv);
//        iv = (int)(lv % Integer.MAX_VALUE);
//        System.out.println("iv2:" + iv);
    }
    
    @Test
    public void testGetNullInt() {
        Map<String, Integer> map = new HashMap<>();
        map.put("a", 1);
        int v = map.get("a");
        assertEquals(v, 1);
        v = 0;
        try {
            v = map.get("b");
            fail("Should not go to here");
        } catch(Exception e) {
            assertEquals(v, 0);
        }
        Integer vv = map.get("b");
        assertNull(vv);
    }

    @Test
    public void testIntegerAbs() {
        //取整型、长整型绝对值时必须考虑此边界
        int v = Integer.MIN_VALUE;
        v = -v;
        assertTrue(v < 0);
    }
    
    @Test
    public void testTlsVersions() {
        try {
            SSLContext context = SSLContext.getDefault();
            String[] protocols = context.getSupportedSSLParameters().getProtocols();

            String maxVer = "";
            String tlsHead = "TLSv";
            System.out.println("Supported Protocols: " + protocols.length);
            for (String p : protocols) {
                System.out.print(" " + p);
                if(p.startsWith(tlsHead) && p.compareTo(maxVer) > 0) {
                    maxVer = p;
                }
            }
            System.out.println();
            assertTrue(maxVer.compareTo("TLSv1.2") >= 0);
        } catch(Exception e) {
            fail("Fail to test tls versions" + e);
        }
    }
    
    @Test
    public void testCalendarUTC() {
        //1、确定本地当前时间
        Calendar cal = Calendar.getInstance();
        //2、取得时间偏移量
//        int zoneOffset = cal.get(java.util.Calendar.ZONE_OFFSET);
        //3、取得夏令时差：
//        int dstOffset = cal.get(java.util.Calendar.DST_OFFSET);
        //4、从本地时间里扣除这些差量，即可以取得UTC日期：
        //cal.add(java.util.Calendar.MILLISECOND, -(zoneOffset + dstOffset));
        
        long cur = System.currentTimeMillis();
        long calUTC = cal.getTimeInMillis();
        System.out.println("1:cur=" + cur + ",cal=" + calUTC + ",difference=" + (cur - calUTC));

        assertTrue(Math.abs(calUTC - cur) < 100);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        calUTC = cal.getTimeInMillis();
        System.out.println("2:cur=" + cur + ",cal=" + calUTC + ",difference=" + (cur - calUTC));
        assertTrue(cur - calUTC >= hour * 3600000L + minute * 60000);

        int days = cal.get(Calendar.DAY_OF_MONTH);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        calUTC = cal.getTimeInMillis();
        System.out.println("3:cur=" + cur + ",cal=" + calUTC + ",difference=" + (cur - calUTC));
        assertTrue(cur - calUTC >= (days-1) * 86400000L + hour * 3600000 + minute * 60000);
        System.out.println("timezone:" + TimeZone.getDefault().getRawOffset()/60000);
    }

    @Test
    public void testAbsPlatform() {
        initUnitTestSystem(); //同时触发UnitTestBase的静态块运行
        File d = AbsPlatform.createTempDirectory("test");
        assertTrue(d.exists());
        File f = AbsPlatform.createTempFile("test");
        FileUtil.writeFile(f, new byte[]{});
        assertTrue(f.exists());
        FileUtil.removeDir(d);
        FileUtil.remove(f);
    }

    @Test
    public void testAsList() {
        String[] ss = new String[]{"a","b","c"};
        List<String> list = Arrays.asList(ss);
        assertEquals(list.size(), 3);
        assertEquals(list.get(1), "b");
    }
    
//    //此为依赖库的功能，本不该在此测试，但是跨平台的原因，保留此测试
//    @Test
//    public void testZipDir() {
//        net.lingala.zip4j.model.ZipParameters zipParameters = new net.lingala.zip4j.model.ZipParameters();
//        zipParameters.setEncryptFiles(true);
//        zipParameters.setIncludeRootFolder(false);
//        zipParameters.setEncryptionMethod(net.lingala.zip4j.model.enums.EncryptionMethod.AES);
//        // Below line is optional. AES 256 is used by default. You can override it to use AES 128. AES 192 is supported only for extracting.
//        zipParameters.setAesKeyStrength(net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256); 
//        
//        String dir = cn.net.zhijian.util.FileUtil.addPath(getHomeDir(), "ziptest");
//        String zfName = cn.net.zhijian.util.FileUtil.addPath(dir, "test.zip");
//        System.out.println(zfName);
//        try(net.lingala.zip4j.ZipFile zf = new net.lingala.zip4j.ZipFile(zfName, "123".toCharArray())) {
//            new java.io.File(dir).mkdirs();
//            long start = System.currentTimeMillis();
//            zf.createSplitZipFileFromFolder(
//                    new java.io.File(cn.net.zhijian.util.FileUtil.addPath(getUserDir(), "services")),
//                    zipParameters,
//                    true, 
//                    50 * 1024 * 1024);
//            long end = System.currentTimeMillis();
//            System.out.println("zip:" + (end - start) + "ms,size:" + zf.getFile().length());
//            start = System.currentTimeMillis();
//            zf.extractAll(cn.net.zhijian.util.FileUtil.addPath(dir, "services"));
//            end = System.currentTimeMillis();
//            System.out.println("unzip:" + (end - start) + "ms");
//        } catch (java.io.IOException e) {
//            fail("Fail to cal zip", e);
//        } finally {
//            cn.net.zhijian.util.FileUtil.removeDir(new java.io.File(dir));
//        }
//    }
    
    @Test
    public void testMapComputeIfAbsent() {
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        map.put("a", 1);
        map.computeIfAbsent("b", k -> {
            //map.put("b", 2); //不必put，运行后已经put了
            return 2;
        });
        Integer b = map.get("b");
        assertTrue(b != null && b == 2);
    }
    
    //测试同一个Future在多个线程中使用
    //在Processor的readCache中会有如此实现
    @Test
    public void testMultiCompletableFuture() throws InterruptedException {
        AtomicInteger v = new AtomicInteger(1);
        ExecutorService pool = Executors.newCachedThreadPool();
        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
            return 1;
        });
        int N = 10;
        CountDownLatch counter = new CountDownLatch(N);
        for(int i = 0; i < N; i++) {
            pool.execute(() -> {
                future.whenComplete((r, e) -> {
                    if(r != 1) {
                        v.set(r);
                    }
                    counter.countDown();
                });
            });
        }
        counter.await();
        assertEquals(v.get(), 1);
    }
}
