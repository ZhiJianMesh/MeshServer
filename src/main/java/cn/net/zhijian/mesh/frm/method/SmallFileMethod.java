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

/**
 * 文件下载处理方法，不区分公共与私有，适合处理短小的文件。
 * 可以根据扩展名，返回不同的content-type
 * 如果同一个文件需要支持不同的环境，可以在文件名的尾部，扩展名的前面，加上环境类型，
 * 当前支持bs、mc、win、linux、android、ios，
 * 分别表示浏览器、移动端、window、linux、android、ios客户端
 * @author flyinmind of csdn.net
 *
 */
public final class SmallFileMethod extends AbsFileMethod {
    public static final String AGENT_MESHCLIENT = "mc"; //mesh client，包括win、linux、android、ios
    private static final String AGENT_BROWSER = "bs";
//    private static final String AGENT_ANDROID = "android"; //android client
//    private static final String AGENT_WIN = "win"; //windows client
//    private static final String AGENT_LINUX = "linux"; //linux client
//    private static final String AGENT_IOS = "ios"; //ios client

    private FileContent[] contents;
    private FileContent backstop; //最后托底的

    public SmallFileMethod(ServiceInfo si, File f, String contentType) {
        this(si, f, contentType, AGENT_MESHCLIENT);
    }

    public SmallFileMethod(ServiceInfo si, File f, String contentType, String agent) {
        super(si, contentType);
        this.backstop = new FileContent(f, agent);
        this.contents = new FileContent[] {this.backstop};
    }

    @Override
    public void response(AbsServerRequest req, AbsServerResponse resp) {
        resp.putHeader(HttpUtil.HEAD_CONTENT_TYPE, contentType);
        FileContent content = backstop; //默认使用第一个
        if(contents.length > 1) { //多个的情况，需要匹配agent
            String agent = req.header(HttpUtil.HEAD_USER_AGENT);
            //mesh客户端的agent都是"操作系统_版本号"，不会有/
            //浏览器用“浏览器名称/版本号”，可能有多个，用空格分隔
            //如果是浏览器，是无法按版本区分的，UI实现时，需要考虑同一个版本中兼容性多个版本的浏览器
            if(StringUtil.isEmpty(agent) || agent.indexOf('/') >= 0) {
                agent = AGENT_BROWSER;
            }
            for(FileContent c : contents) {
                if(agent.startsWith(c.agent)) {
                    content = c;
                    break;
                }
            }
        }

        content.response(req, resp);
    }

    /**
     * 需要适配不同环境，存在多个版本的内容
     * @param f 文件名
     * @param agent 类型，有mc、bs、win等
     */
    public void addFile(File f, String agent) {
        int len = this.contents.length;
        FileContent[] contents = new FileContent[len + 1];
        System.arraycopy(this.contents, 0, contents, 0, len);
        contents[len] = new FileContent(f, agent);
        if(AGENT_MESHCLIENT.equals(agent)) {
            backstop = contents[len]; //兼容所有操作系统mesh客户端的版本
        }
        this.contents = contents;
    }
    
    /**
     * 读取文件内容，如果内容不超过MAX_CACHE_SIZE，则缓存在内存中，
     * 缓存后，会定时刷新，如果cacheTime为0，则不缓存
     * @author flyinmind of csdn.net
     */
    static class FileContent {
        private static int cacheTime = 300 * 1000;

        public final File file;
        public final String agent;

        private String etag;
        private long expireAt;
        private byte[] content;
        private int len;

        FileContent(File fn, String agent) {
            this.file = fn;
            this.expireAt = 0;
            this.etag = FileUtil.digest(fn);
            this.agent = agent;
        }

        void response(AbsServerRequest req, AbsServerResponse resp) {
            String reqETag = req.headers().get(HEAD_NONEMATCH);

            /*
             * 小文件使用缓存减少磁盘访问，同时使用etag减少返回，
             * 重启后etag一定发生变化，运行中，etag定期变化
             */
            long cur = System.currentTimeMillis();
            if(this.expireAt < cur) {
                byte[] buf = FileUtil.readFile(this.file, true);
                if(buf == null || buf.length == 0) {
                    resp.error(HttpResponseStatus.NOT_FOUND, RetCode.NOT_EXISTS);
                    return;
                }

                String etag = ByteUtil.bin2hex(SecureUtil.md5(buf));
                this.len = buf.length;
                this.content = buf;
                this.etag = etag;
                this.expireAt = cur + cacheTime;

                if(!StringUtil.isEmpty(reqETag) && etag.equals(reqETag)) {
                    resp.error(HttpResponseStatus.NOT_MODIFIED, RetCode.EXISTS);
                    return;
                }
            }
            if(!StringUtil.isEmpty(reqETag) && reqETag.equals(this.etag)) {
                resp.error(HttpResponseStatus.NOT_MODIFIED, RetCode.EXISTS);
                return;
            }
            resp.putHeader(HEAD_ETAG, this.etag);
            resp.end(this.content, 0, this.len);
        }
    }
    

    @Override
    public void destroy() {
        for(FileContent fc : this.contents) {
            if(fc.content != null) {
                try {//会提示已释放，但是在重新加载内容的地方release不会
                    fc.content = null;
                } catch(Exception ignored) {}
            }
        }
    }

    /**
     * 设置默认的缓存时间
     * @param cacheTime 缓存时间，单位毫秒
     */
    public static void setDefaultCacheTime(int cacheTime) {
        SmallFileMethod.FileContent.cacheTime = cacheTime;
    }
}
