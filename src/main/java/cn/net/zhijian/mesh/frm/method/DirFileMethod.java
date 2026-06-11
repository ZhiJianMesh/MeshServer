package cn.net.zhijian.mesh.frm.method;

import java.io.IOException;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.BigFileReader;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsFileMethod;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * 目录文件下载实现，一次指定同目录下所有文件，程序必须拥有目录的读权限
 * 无论大小，都是按照大文件方式发送，以chunked或range方式发送
 *
 * @author flyinmind of csdn.net
 *
 */
public class DirFileMethod extends AbsFileMethod {
    private static final Logger LOG = LogUtil.getInstance();
    private final String homeDir;

    public DirFileMethod(ServiceInfo si, String homeDir) {
        super(si, null);
        this.homeDir = homeDir;
    }

    @Override
    public void response(AbsServerRequest req, AbsServerResponse resp) {
        String name = getFileName(req);
        if(name.contains("./")) {
            LOG.error("Invalid file name {}", name);
            resp.error(HttpResponseStatus.BAD_REQUEST, RetCode.WRONG_PARAMETER);
            return;
        }
        String fn = FileUtil.addPath(homeDir, name);
        if(!FileUtil.isExist(fn)) {
            resp.error(HttpResponseStatus.NOT_FOUND, RetCode.NOT_EXISTS);
            return;
        }
        String fileNo = SecureUtil.md5(fn);
        BigFileReader bfr = BigFileReader.get(fileNo);
        
        String contentType = getContentType(FileUtil.getFileExtension(name));
        if(bfr == null) {
            synchronized(this) {
                try {
                    bfr = BigFileReader.add(fileNo, fn);
                } catch (IOException e) {
                    LOG.warn("{} not exists", fn, e);
                    resp.error(HttpResponseStatus.NOT_FOUND, RetCode.NOT_EXISTS);
                    return;
                }
            }
        }
        sendBigFile(req, resp, bfr, name, contentType);
    }
    
    /**
     * 返回真实的文件目录，形如 /classhour/0.1.0/server.zip
     * 在appstore的file目录下，不可以出现类型的目录结构
     */
    protected String getFileName(AbsServerRequest req) {
        return req.getString("n");
    }
}