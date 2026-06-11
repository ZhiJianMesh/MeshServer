package cn.net.zhijian.mesh.bean;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.LruCache;

/**
 * 大文件读取
 * 所有文件句柄放在一个LruCache缓存中，用于解决多线程读取相同文件的问题。
 * 读完之后并不会立刻关闭，只有在缓存数量超过上限时，才会真正关闭
 */
public class BigFileReader implements Closeable {
    private static final int MAX_FILE_NUM = 50;
    /*
     * 正在下载种的文件列表，总数MAX_FILE_NUM个
     * 如果超过MAX_FILE_NUM，则会丢弃不常用的，同时会关闭文件
     */
    private static final LruCache<String, BigFileReader> Files = new LruCache<>(MAX_FILE_NUM);

    public final long size; //最大2G
    public final String digest; //摘要
    private UncloseableRandomAccessFile file;
    
    public BigFileReader(File file) throws IOException {
        long len = file.length();
        if(len > Integer.MAX_VALUE) { //不接受超过2G的文件
            throw new IOException("Too large file " + file);
        }
        this.size = len;
        this.file = new UncloseableRandomAccessFile(file, "r");
        this.digest = FileUtil.digest(file);
    }

    public synchronized byte[] read(long start, int len) throws IOException {
        this.file.seek(start);
        int readLen = (int)Math.min(size - start, len);
        byte[] content = new byte[readLen];
        this.file.read(content);
        return content;
    }
    
    public synchronized int read(long start, byte[] buff) throws IOException {
        this.file.seek(start);
        int readLen = (int)Math.min(size - start, buff.length);
        this.file.read(buff, 0, readLen);
        return readLen;
    }
    
    public RandomAccessFile file() {
        return this.file;
    }
    
    @Override
    public synchronized void close() {
        if(this.file != null) {
            try {
                this.file.realClose();
            } catch (IOException e) {
            }
            this.file = null;
        }
    }

    public static BigFileReader add(String key, String fileName) throws IOException {
        File f = new File(fileName);
        if(!f.exists()) {
            throw new FileNotFoundException(fileName);
        }
        BigFileReader bfr = Files.get(key);
        if(bfr != null) {
            return bfr;
        }
        bfr = new BigFileReader(f);
        Files.put(key, bfr);
        return bfr;
    }
    
    public static BigFileReader get(String key) {
        return Files.get(key);
    }
    
    
    private static class UncloseableRandomAccessFile extends RandomAccessFile {
        private final String name;
        
        public UncloseableRandomAccessFile(File file, String mode) throws FileNotFoundException {
            super(file, mode);
            this.name = file.getPath();
        }
        
        @Override
        public void close() {//避免被其他进程关闭，只有从Files缓存中移除时才会真正关闭
        }
        
        void realClose() throws IOException {
            super.close();
        }
        
        @Override
        public String toString() {
            return name;
        }
    }
}
