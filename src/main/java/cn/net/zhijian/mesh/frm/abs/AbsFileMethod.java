package cn.net.zhijian.mesh.frm.abs;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.BigFileReader;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IMethod;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.HttpUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;

public abstract class AbsFileMethod implements IMethod {
    private static final Logger LOG = LogUtil.getInstance();
    public static final String CONTENTTYPES_FILE = "content_types.cfg";

    public static final String CONTENTTYPE_BINARY = "application/octet-stream";
    //响应头，唯一标识文件，请求时在IF_NONE_MATCH中携带
    public static final String HEAD_ETAG = HttpHeaderNames.ETAG.toString().toLowerCase();
    //请求头，唯一标识文件，如果已存在，则不必在此发送，返回NOT_MODIFIED即可
    public static final String HEAD_NONEMATCH = HttpHeaderNames.IF_NONE_MATCH.toString().toLowerCase();

    private static final Map<String, String> contentTypes = new HashMap<>();
    public final ServiceInfo serviceInfo;
    public final String contentType;

    public AbsFileMethod(ServiceInfo serviceInfo, String contentType) {
        this.serviceInfo = serviceInfo;
        this.contentType = contentType;
    }
    /**
     * 读取文件内容
     * @param req 请求体
     * @param resp 响应体，如果读取成功，直接写入resp
     */
    public abstract void response(AbsServerRequest req, AbsServerResponse resp);
    
    public static String getContentType(String ext) {
        String s = contentTypes.get(ext);
        if (s == null) {
            LOG.warn("Invalid file type '{}', use {} as default", ext, CONTENTTYPE_BINARY);
            return CONTENTTYPE_BINARY;
        }
        return s;
    }

    /**
     * 加载content_types.cfg文件，
     * 记录文件扩展明对应的content-type，
     * 在FileMethod中会自动根据扩展名，填写相应的content-type。
     * 每次重新启动都要加载，使用json解析比较慢，所以改成直接的字符串处理，
     * 改了之后，在I5-8上大约3ms，
     * 服务侧受影响不大，为了与端侧保持一致，改用相同的格式
     * @return 异步结果
     */
    public static HandleResult loadContentTypes() {
        if (!contentTypes.isEmpty()) { //加载过了
            LOG.info("ContentTypes already loaded,size:{}", contentTypes.size());
            return HandleResult.OK;
        }

        try(InputStream in = AbsAssets.instance().open(CONTENTTYPES_FILE)) {
            return loadContentTypes(in);
        } catch (IOException e) {
            LOG.warn("Fail to read {}", CONTENTTYPES_FILE, e);
            return new HandleResult(RetCode.INTERNAL_ERROR, "fail to read " + CONTENTTYPES_FILE);
        }
    }

    public static HandleResult loadContentTypes(InputStream in) {
        if (!contentTypes.isEmpty()) { //加载过了
            LOG.info("ContentTypes already loaded,size:{}", contentTypes.size());
            return HandleResult.OK;
        }

        byte[] data = FileUtil.readStream(in);
        if (data == null || data.length == 0) {
            LOG.warn("Invalid content types file, it is null");
            return new HandleResult(RetCode.DATA_WRONG, "file is null");
        }
        int start = 0;
        int sep = 0;
        int l = 0;
        int i;
        int len = data.length;
        String k;
        String v;

        for (i = 0; i < len; i++) {
            if (data[i] == '\n' || data[i] == '\r') {
                if (l == 0 || sep <= start) {
                    start = i + 1;
                    continue; //windows文本文件\r\n相连，或者空行的情况
                }
                k = new String(data, start, sep - start).trim();
                v = new String(data, sep + 1, i - sep - 1).trim();
                contentTypes.put(k, v);
                l = 0;
                start = i + 1;
            } else if (data[i] == ':') {
                sep = i;
                l++;
            } else {
                l++;
            }
        }
        if (l > 0 && sep > start) { //最后一样没有换行的情况
            k = new String(data, start, sep - start).trim();
            v = new String(data, sep + 1, i - sep - 1).trim();
            contentTypes.put(k, v);
        }
        return HandleResult.OK;
    }
    
    public static void sendBigFile(AbsServerRequest req, AbsServerResponse resp, BigFileReader bfr, String name, String contentType) {
        String reqETag = req.headers().get(HEAD_NONEMATCH);
        resp.putHeader(HttpUtil.HEAD_CONTENT_TYPE, contentType);
        resp.putHeader(HEAD_ETAG, bfr.digest);
        resp.putHeader(HttpUtil.HEAD_CONTENTMD5, bfr.digest);
        String attachmentName = FileUtil.getFileName(name);
        if(!StringUtil.isEmpty(attachmentName)) {
            String n = UrlPathInfo.urlEncode(attachmentName); //因为安卓版本问题，不可以使用UrlEncoder
            resp.putHeader(HttpUtil.HEAD_ATTACHMENT, "attachment;filename=" + n);
        }
        
        String range = req.headers().get(HttpUtil.HEAD_RANGE);
        if(StringUtil.isEmpty(range) //没有range，则使用chunk方式发送
           || !range.startsWith(HttpUtil.RANGE_REQ_BYTES)) {
            if(bfr.digest.equals(reqETag)) { //etag未变，无需再次下载
                resp.error(HttpResponseStatus.NOT_MODIFIED, RetCode.EXISTS);
                return;
            }
            if(bfr.size < AbsServerResponse.CHUNK_SIZE) {
                sendSmallFile(resp, bfr);
            } else {
                resp.sendChunkedFile(bfr);
            }
        } else {//有正确的range，则执行分片下载
            if(reqETag != null && !bfr.digest.equals(reqETag)) { //文件中途变化了，则将文件全部再发一次
                if(bfr.size < AbsServerResponse.CHUNK_SIZE) {
                    sendSmallFile(resp, bfr);
                } else {
                    resp.sendChunkedFile(bfr);
                }
                return;
            }
            sendRangedFile(range, resp, bfr);
        }
    }
    
    private static void sendRangedFile(String range, AbsServerResponse resp, BigFileReader bfr) {
        //只接受0-，a-b，a-，不接受多个分段
        String r = range.substring(HttpUtil.RANGE_REQ_BYTES.length());
        int pos = r.indexOf('-');
        if(pos < 0 || r.indexOf(',') >= 0) {
            resp.error(HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE, RetCode.WRONG_PARAMETER);
            LOG.warn("Invalid range {}", range);
            return;
        }

        long start = StringUtil.parseLong(r.substring(0, pos), 0);
        long end = StringUtil.parseLong(r.substring(pos + 1), bfr.size - 1);
        if(start < 0 || start > end || start >= bfr.size - 1) {
            resp.error(HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE, RetCode.WRONG_PARAMETER);
            LOG.warn("Invalid range start:{},end:{},filesize:{}", start, end, bfr.size);
            return;
        }
        if(end >= bfr.size - 1) {
            end = bfr.size - 1;
        }
        
        int len = (int)(end - start + 1); //闭合区间，包括开始与结束
        try {
            byte[] buf = bfr.read(start, len);
            resp.putHeader(HttpUtil.HEAD_CONTENTRANGE, HttpUtil.RANGE_RESP_BYTES + start + '-' + end + '/' + bfr.size);
            resp.setStatus(HttpResponseStatus.PARTIAL_CONTENT, RetCode.OK);
            resp.end(buf, 0, buf.length);
        } catch (IOException e) {
            resp.error(HttpResponseStatus.INTERNAL_SERVER_ERROR, RetCode.INTERNAL_ERROR);
        }
    }

    private static void sendSmallFile(AbsServerResponse resp, BigFileReader bfr) {
        try {
            byte[] buf = bfr.read(0, (int)bfr.size);//一次全部读完，一次发送
            resp.end(buf, 0, buf.length);
        } catch (IOException e) {
            resp.error(HttpResponseStatus.INTERNAL_SERVER_ERROR, RetCode.INTERNAL_ERROR);
        }
    }

    @Override
    public boolean isPrivate() {
        return false;
    }

    @Override
    public boolean isValidMethod(String method) {
        return true;
    }

    @Override
    public void destroy() {
    }
}
