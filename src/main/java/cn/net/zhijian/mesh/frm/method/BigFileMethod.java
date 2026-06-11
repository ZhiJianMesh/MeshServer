package cn.net.zhijian.mesh.frm.method;

import java.io.File;
import java.io.IOException;

import cn.net.zhijian.mesh.bean.BigFileReader;
import cn.net.zhijian.mesh.frm.abs.AbsFileMethod;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.FileUtil;

/**
 * 大文件下载实现
 * SmallFileMethod适合小文件传输，默认小于64K的文件都保存在内存中，一次读取全部返回。
 * 超过64K的，如果传递了Range头，则分片返回，否则以chunked方式返回，每个chunk 8192字节
 *
 * @author flyinmind of csdn.net
 *
 */
public class BigFileMethod extends AbsFileMethod {
    private final BigFileReader file;
    private final String attachmentName;

    public BigFileMethod(ServiceInfo si, File f, String contentType) throws IOException {
        super(si, contentType);
        this.file = new BigFileReader(f);
        this.attachmentName = FileUtil.getFileName(f.getCanonicalPath());
    }

    @Override
    public void response(AbsServerRequest req, AbsServerResponse resp) {
        sendBigFile(req, resp, this.file, attachmentName, contentType);
    }
}