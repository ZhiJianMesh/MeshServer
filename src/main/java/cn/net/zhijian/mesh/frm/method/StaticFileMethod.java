package cn.net.zhijian.mesh.frm.method;

import java.io.File;

import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsFileMethod;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.HttpUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import io.netty.handler.codec.http.HttpResponseStatus;


public class StaticFileMethod extends AbsFileMethod {
    private final File fn;
    private final byte[] defaultContent; //无法读取时的返回内容
    private String etag;

    public StaticFileMethod(ServiceInfo si, String file, String contentType, byte[] defaultContent) {
        super(si, contentType);
        this.fn = new File(file);//缓存了，文件系统中的文件可以暂时不存在
        this.defaultContent = defaultContent;
    }

    @Override
    public void response(AbsServerRequest req, AbsServerResponse resp) {
        if(!StringUtil.isEmpty(etag)) {
            String reqETag = req.headers().get(HEAD_NONEMATCH);
            if(etag.equals(reqETag)) {
                resp.error(HttpResponseStatus.NOT_MODIFIED, RetCode.EXISTS);
                return;
            }
        }

        resp.putHeader(HttpUtil.HEAD_CONTENT_TYPE, contentType);
        byte[] content;
        if(fn.exists()) {
            content = FileUtil.readFile(fn, false);
            etag = ByteUtil.stdBase64Encode(SecureUtil.md5(content));
            resp.putHeader(HEAD_ETAG, etag);
        } else {
            content = defaultContent;
        }
        if(content != null) {
            resp.end(content, 0, content.length);
        } else {
            resp.end(new byte[]{} , 0, 0);
        }
    }
}
