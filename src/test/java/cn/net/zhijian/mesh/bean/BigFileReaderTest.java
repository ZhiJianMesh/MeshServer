package cn.net.zhijian.mesh.bean;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.fileq.util.FileUtil;

public class BigFileReaderTest extends UnitTestBase {
    @Test
    public void testBigRead() {
        File f = new File(FileUtil.addPath(configDir, "dictionary.txt"));
        long size = f.length();
        try(BigFileReader bfr = new BigFileReader(f)) {
            testRead(bfr, size);
        } catch(IOException e) {
            fail("Fail to read " + f, e);
        }
    }
    
    private void testRead(BigFileReader bfr, long size) throws IOException {
        byte[] buf = new byte[1024 * 1024];
        long start = 0L;
        int readLen = 0;

        do {
            start += readLen;
            readLen = bfr.read(start, buf);
        } while(readLen > 0);
        assertEquals(start, size);
    }
    
    public void testMultiThreadBigRead() {
        final int N = 4;
        CountDownLatch counter = new CountDownLatch(N);
        File f = new File(FileUtil.addPath(configDir, "dictionary.txt"));
        long size = f.length();
        try(BigFileReader bfr = new BigFileReader(f)) {
            for(int i = 0; i < N; i++) {
                new Thread() {
                    public void run() {
                        try {
                            testRead(bfr, size);
                        } catch(IOException e) {
                            fail("Fail to read " + f, e);
                        } finally {
                            counter.countDown();
                        }
                    }
                }.start();
            }
            counter.await();
        } catch(IOException | InterruptedException e) {
            fail("Fail to read " + f + " in multi-thread", e);
        }
    }
}
