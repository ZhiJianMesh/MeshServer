package cn.net.zhijian.platform.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import cn.net.zhijian.mesh.frm.abs.AbsAssets;

/**
 * 附件工具类，用于适配不同平台中资源文件的处理，
 * 包括读取配置等。安卓中使用AssetManager，java中直接使用ClassLoader加载
 * @author flyinmind of csdn.net
 *
 */
public final class AssetsWrapper extends AbsAssets {
    private final ClassLoader loader;
    
    public AssetsWrapper(ClassLoader loader) {
        this.loader = loader;
    }

    @Override
    public InputStream open(String name) throws IOException {
        return loader.getResourceAsStream(name);
    }

    @Override
    public String[] list(String path) throws IOException {
        List<String> fileNames = new ArrayList<>();

        // 1. 解析 ClassPath 中的所有 URL（可能是目录、Jar 包）
        Enumeration<URL> resources = loader.getResources(path);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            String protocol = url.getProtocol();

            // 2. 处理 "file" 协议（普通目录，如开发环境的 target/classes）
            if ("file".equals(protocol)) {
                String filePath = URLDecoder.decode(url.getFile(), StandardCharsets.UTF_8.name());
                fileNames.add(filePath);
            }
        }

        return fileNames.toArray(new String[] {});
    }

    /**
     *
     * @param file 文件名
     * @return 0不存在，1文件，2目录
     */
    @Override
    public AssetType fileType(String file) {
        try {
            Enumeration<URL> resources = loader.getResources(file);
            if (!resources.hasMoreElements()) {
                return AssetType.NotExists; // 资源不存在
            }
            
            URL url = resources.nextElement();
            String protocol = url.getProtocol();
            if ("file".equals(protocol)) {
                 String filePath = URLDecoder.decode(url.getFile(), StandardCharsets.UTF_8.name());
                 File f = new File(filePath);
                 if (!f.exists()) {
                     return AssetType.NotExists;
                 }
                 return f.isFile() ? AssetType.File : AssetType.Directory;
            }
        } catch (IOException e) {
        }
        return AssetType.NotExists;
    }
}
