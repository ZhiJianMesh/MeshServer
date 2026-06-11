package cn.net.zhijian.mesh.frm.abs;

import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.IUtil;
import cn.net.zhijian.util.LogUtil;

/**
 * 处理只读的资源文件
 * @author flyinmind of csdn.net
 */
public abstract class AbsAssets {
    private static final Logger LOG = LogUtil.getInstance();
    private static AbsAssets instance = null;
    /**
     * assets路径的类型
     */
    public enum AssetType {NotExists, File, Directory}

    public abstract InputStream open(String name) throws IOException;

    public abstract String[] list(String path) throws IOException;

    public abstract AssetType fileType(String file);

    public static void init(AbsAssets wrapper) {
        AbsAssets.instance = wrapper;
    }
    
    public static AbsAssets instance() {
        return AbsAssets.instance;
    }
    
    public byte[] read(String name) throws IOException {
        try (InputStream is = open(name)) {
            return FileUtil.readStream(is);
        }
    }

    public String readString(String file) throws IOException {
        try (InputStream is = open(file)) {
            byte[] content = FileUtil.readStream(is);
            if (content == null) {
                return null;
            }
            return new String(content, IUtil.DEFAULT_CHARSET);
        }
    }

    /**
     * 从assets拷贝文件或目录，拷贝时如果是目录，则创建它，如果是文件，则复制它。
     * 目标路径使用 dstDir + src，所以在递归时dstDir不变，而srcDir需要变化
     *
     * @param src    assets中的目录或文件名称
     * @param dstDir 目标目录，通常指应用的files目录
     * @throws IOException IO异常
     */
    public void copyDir(String src, String dstDir) throws IOException {
        copyDir(src, src, dstDir);
    }

    private void copyDir(String srcRoot, String src, String dstDir) throws IOException {
        if(!src.startsWith(srcRoot)) {
            throw new IOException("Invalid src dir, not starts with root");
        }
        String[] fileList = list(src);
        String dst;
        if(src.length() == srcRoot.length()) {
            dst = dstDir;
        } else {
            dst = FileUtil.addPath(dstDir, src.substring(srcRoot.length()));
        }
        if (fileList != null && fileList.length > 0) {
            LOG.debug("There {} files under {}", fileList.length, src);
            File fp = new File(dst);
            if (!fp.exists()) {//是目录，目的端不存在则创建，并且开始递归复制
                if (!fp.mkdirs()) {
                    throw new IOException("Fail to create dir " + dst);
                }
            }

            for (String fileName : fileList) {
                String srcDir = FileUtil.addPath(src, fileName);
                copyDir(srcRoot, srcDir, dstDir);
            }
        } else {//如果是文件则拷贝(安卓打包时会去除assets下的空目录，java环境可能是一个空目录)
            LOG.debug("Copy {} to {}", src, dst);
            try (InputStream inputStream = open(src)) {
                if (inputStream == null) {
                    throw new IOException("Fail to open " + src);
                }
                FileUtil.copyStream(inputStream, dst);
            }
        }
    }

    public long copyStream(String src, String dstFile) throws IOException {
        return FileUtil.copyStream(open(src), dstFile);
    }

    public long copyStream(String src, File dstFile) throws IOException {
        return FileUtil.copyStream(open(src), dstFile);
    }
}
