package cn.net.zhijian.mesh.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.HttpCallback;
import cn.net.zhijian.util.HttpResp;
import cn.net.zhijian.util.HttpUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

public abstract class OssClient extends HttpClient {
    //providers
    //public static final String ALIOSS = "ALI"; //阿里云
    //public static final String HWOSS = "HW"; //华为
    //public static final String AWSOSS = "AWS"; //亚马逊
    //public static final String TXOSS = "TX"; //腾讯

    protected static final String SCHEME = "https://";
    private static final int AVERAGE_SPEED = 60 * 1024;//按1秒60KB估计超时时间
    /** 超过此大小的对象改用分片传输；分片只解决"单请求超时"，不改总带宽。
     *  根服务按同一个阈值决定是否下发分片方案，两端必须一致 */
    public static final int MULTIPART_THRESHOLD = 4 * 1024 * 1024;
    /** 分片大小，会按片数上限自动放大（见partSize） */
    private static final int MULTIPART_PART_SIZE = 1024 * 1024;
    /** 单片失败重试次数 */
    private static final int PART_RETRY = 3;

    private static final Logger LOG = LogUtil.getInstance();

    protected final String keyId;
    protected final byte[] key;

    protected final String bucket;
    protected final String innerEndPoint;
    protected final String externalEndPoint;
    
    public OssClient(String innerEndPoint, String externalEndPoint,
            String bucket, String keyId, String key) {
        this.innerEndPoint = innerEndPoint;
        this.externalEndPoint = externalEndPoint;
        this.bucket = bucket;
        this.keyId = keyId;
        this.key = key.getBytes(IConst.DEFAULT_CHARSET);
    }

    /**
     * 生成给第三方直接下载的url，只能使用外网
     * @param endPoint 站点host
     * @param objName 对象名称
     * @param contentType 类型
     * @param contentMd5 校验码
     * @param pwd 对象密码
     * @param size 文件大小
     * @return 下载URL及头信息
     */
    public abstract UrlRequest getUrl(String endPoint, String objName,
            String contentType, String contentMd5, String pwd, int size);

    /**
     * 生成给第三方直接删除的url，只能使用外网
     * @param endPoint 站点host
     * @param objName 对象名称
     * @return 下载URL及头信息
     */
    public abstract UrlRequest deleteUrl(String endPoint, String objName);

    /**
     * 生成给第三方直接上传的url
     * @param endPoint 站点host
     * @param objName 对象名称
     * @param contentType 类型
     * @param contentMd5 校验码
     * @param size 大小
     * @param validTime url有效时间，以秒为单位
     * @return 上传URL及头信息
     */
    public abstract UrlRequest putUrl(String endPoint, String objName,
            String contentType, String contentMd5, String pwd, int size, int validTime);

    /**
     * 生成分片上传方案（预签名URL集合）。仅根环境（持有密钥）能签名，业务实例返回null。
     * @param endPoint 站点host
     * @param objName 对象名称
     * @param contentType 类型
     * @param contentMd5 校验码
     * @param pwd 对象密码
     * @param size 文件大小
     * @param validTime url有效时间，以秒为单位
     * @return 分片方案；小文件或非根环境返回null的future
     */
    public abstract CompletableFuture<Map<String, Object>> multipartUrl(String endPoint,
            String objName, String contentType, String contentMd5, String pwd, int size, int validTime);

    public UrlRequest getUrl(String objName, String contentType,
            String contentMd5, String pwd, int size) {
        return getUrl(externalEndPoint, objName, contentType, contentMd5, pwd, size);
    }
    
    public UrlRequest putUrl(String objName, String contentType, String contentMd5,
            String pwd, int size, int validTime) {
        return putUrl(externalEndPoint, objName, contentType, contentMd5, pwd, size, validTime);
    }

    public CompletableFuture<Map<String, Object>> multipartUrl(String objName, String contentType,
            String contentMd5, String pwd, int size, int validTime) {
        return multipartUrl(externalEndPoint, objName, contentType, contentMd5, pwd, size, validTime);
    }

    public static CompletableFuture<HandleResult> upload(UrlRequest req, File locFile) {
        long size = locFile.length();
        HttpUtil http = getHttpClient(predictedHttpTime(size)); //ms
        String contentType = req.headers.get(HttpUtil.HEAD_CONTENT_TYPE);

        AbsObsCallback future = new AbsObsCallback(req) {
            @Override
            public HandleResult onSuccess(HttpResp resp) {
                if (!resp.isSuccessful()) {
                    LOG.error("upload({}) failed, status code:{}", req.url, resp.code());
                    return new HandleResult(RetCode.THIRD_PARTY_ERR, errMsg(resp));
                }

                if(LOG.isDebugEnabled()) {
                    long useTime = System.currentTimeMillis() - req.startAt;
                    LOG.debug("upload({}):use time={},size={},speed={}", req.url, useTime, size, (1000L * size / useTime));
                }
                return HandleResult.OK;
            }

            @Override
            protected void request() {
                http.asyncPut(req.url, req.headers, contentType, locFile, this/*重新请求时，本future并没有complete*/);
            }
        };

        http.asyncPut(req.url, req.headers, contentType, locFile, future);
        return future;
    }
    
    /**
     * 下载对象。大文件（超过{@link #MULTIPART_THRESHOLD}）自动改为分片下载：
     * 用HTTP Range把整个GET拆成若干小GET，每个请求都远小于HttpRequest.timeout（整个过程的总时长），
     * 从根本上避开“大文件GET超时”，且单分片失败只重下该分片。调用方无需感知。
     * @param req 预签名GET请求（headers里不要带Range，由本方法按分片设置）
     * @param saveAs 保存为
     * @param size 对象大小；未知时传0，退化为单次GET
     * @return 执行结果
     */
    public static CompletableFuture<HandleResult> download(UrlRequest req, File saveAs, int size) {
        return size > MULTIPART_THRESHOLD ? multipartDownload(req, saveAs, size)
                                          : singleGet(req, saveAs, size);
    }

    /**
     * 单次GET下载
     * @param req 预签名GET请求
     * @param saveAs 保存为
     * @param size 对象大小
     * @return 执行结果
     */
    protected static CompletableFuture<HandleResult> singleGet(UrlRequest req, File saveAs, int size) {
        HttpUtil http = getHttpClient(predictedHttpTime(size));//ms
        AbsObsCallback future = new AbsObsCallback(req) {
            @Override
            public HandleResult onSuccess(HttpResp resp) {
                if (!resp.isSuccessful()) {
                    LOG.error("download({}) failed, status code:{}", req.url, resp.code());
                    return new HandleResult(RetCode.THIRD_PARTY_ERR, errMsg(resp));
                }

                try (InputStream body = resp.body()) {
                    if(body == null) {
                        return new HandleResult(RetCode.THIRD_PARTY_ERR, "invalid response,body is null");
                    }
                    long got = FileUtil.copyStream(body, saveAs);
                    if(LOG.isDebugEnabled()) {
                        long useTime = Math.max(1, System.currentTimeMillis() - req.startAt);
                        LOG.debug("download({}):use time={},size={},speed={}", req.url, useTime, got, (1000L * got / useTime));
                    }
                    return HandleResult.OK;
                } catch (Exception e) {
                    LOG.error("Fail to get response from {}", resp.url(), e);
                    return new HandleResult(RetCode.THIRD_PARTY_ERR, "fail to save response:" + e.getMessage());
                }
            }

            @Override
            protected void request() {
                http.asyncGet(req.url, req.headers, this);
            }
        };

        http.asyncGet(req.url, req.headers, future);
        return future;
    }

    /**
     * 分片下载：按{@link #MULTIPART_PART_SIZE}把对象切成若干段，每段一个带Range头的GET，
     * 读到的内容按偏移写入同一个文件。串行执行（顺序写，失败只重下当前段）；
     * 服务端不支持Range（返回200整包）时改为一次写入，后续分片不再请求。
     * 预签名URL的签名不包含Range，所以业务实例没有密钥也能分片下载。
     * @param req 预签名GET请求
     * @param saveAs 保存为
     * @param size 对象大小
     * @return 执行结果
     */
    private static CompletableFuture<HandleResult> multipartDownload(UrlRequest req, File saveAs, int size) {
        File parent = saveAs.getParentFile();
        if(parent != null && !parent.exists() && !FileUtil.createDir(parent)) {
            LOG.error("Fail to create dir {} for {}", parent, saveAs);
            return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to create dir " + parent);
        }

        RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(saveAs, "rw");
            raf.setLength(0); //丢掉上次残留的内容，避免末尾多出脏数据
        } catch (IOException e) {
            LOG.error("Fail to open `{}` for multipart download", saveAs, e);
            return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to open " + saveAs);
        }

        int partSize = partSize(size);
        int total = (int)(((long)size + partSize - 1) / partSize);
        if(LOG.isDebugEnabled()) {
            LOG.debug("Download `{}`({} bytes,{} parts,{} bytes/part) by multipart",
                    req.objName, size, total, partSize);
        }

        AtomicBoolean wholeFile = new AtomicBoolean(false); //服务端忽略Range时置位，后续分片不再请求
        long startAt = System.currentTimeMillis();
        CompletableFuture<HandleResult> future = CompletableFuture.completedFuture(HandleResult.OK);
        for(int i = 0; i < total; i++) {
            long start = (long)i * partSize;
            int len = (int)Math.min((long)partSize, (long)size - start);
            future = future.thenCompose(hr -> hr.code != RetCode.OK || wholeFile.get()
                    ? CompletableFuture.completedFuture(hr)
                    : new PartDownload(req, raf, start, len, size, wholeFile).start());
        }

        return future.whenComplete((hr, ex) -> {
            closeQuietly(raf);
            if(ex != null || hr == null || hr.code != RetCode.OK) {
                FileUtil.remove(saveAs); //半成品没有保留价值，避免上层拿到残缺文件
                return;
            }
            if(LOG.isDebugEnabled()) {
                long useTime = Math.max(1, System.currentTimeMillis() - startAt);
                LOG.debug("multipart download(`{}`):use time={},size={},parts={},speed={}",
                        req.objName, useTime, size, total, (1000L * size / useTime));
            }
        });
    }

    /**
     * 单个分片的下载：带Range头发GET，失败按{@link #PART_RETRY}重试，
     * 成功后按响应里的偏移写入文件，多分片/多次重试互不干扰。
     */
    private static final class PartDownload extends CompletableFuture<HandleResult> implements HttpCallback {
        private final UrlRequest req;
        private final RandomAccessFile raf;
        private final long start;
        private final int len;
        private final int totalSize;
        private final AtomicBoolean wholeFile;
        private int retried;

        PartDownload(UrlRequest req, RandomAccessFile raf, long start, int len,
                int totalSize, AtomicBoolean wholeFile) {
            this.req = req;
            this.raf = raf;
            this.start = start;
            this.len = len;
            this.totalSize = totalSize;
            this.wholeFile = wholeFile;
        }

        CompletableFuture<HandleResult> start() {
            request();
            return this;
        }

        private void request() {
            //不能直接改req.headers，否则重试/其他分片的Range会互相覆盖
            Map<String, String> headers = new HashMap<>();
            if(req.headers != null) {
                headers.putAll(req.headers);
            }
            headers.put(HttpUtil.HEAD_RANGE, HttpUtil.RANGE_REQ_BYTES + start + '-' + (start + len - 1));
            getHttpClient(predictedHttpTime(len)).asyncGet(req.url, headers, this);
        }

        @Override
        public void onResponse(HttpResp resp) {
            PartResult pr = save(resp);
            if(pr == PartResult.OK) {
                complete(HandleResult.OK);
                return;
            }
            if(pr.retryable && retry(pr.err)) {
                return;
            }
            complete(new HandleResult(pr.code, pr.err));
        }

        @Override
        public void onFailure(Throwable e, String url, String method) {
            if(retry(e.toString())) {
                return;
            }
            String msg = e.getMessage() == null ? "internal exception" : e.getMessage();
            complete(new HandleResult(RetCode.INTERNAL_ERROR, msg));
        }

        /** 重发当前分片；返回true表示已重发，本回调不必再complete */
        private boolean retry(String reason) {
            if(retried >= PART_RETRY) {
                return false;
            }
            retried++;
            LOG.warn("Retry part {}-{} of `{}` after {}/{}:{}",
                    start, start + len - 1, req.objName, retried, PART_RETRY, reason);
            request();
            return true;
        }

        private PartResult save(HttpResp resp) {
            int code = resp.code();
            if(code == 200) { //服务端没有按Range返回，而是整个对象，直接写完即可
                return saveWhole(resp);
            }
            if(code != 206) {
                LOG.error("download(`{}`) part {}-{} failed, status code:{}",
                        req.objName, start, start + len - 1, code);
                boolean retryable = code >= 500 || code == 408 || code == 429;
                return new PartResult(RetCode.THIRD_PARTY_ERR, errMsg(resp), retryable);
            }

            //以服务端实际返回的区间为准写文件，避免个别代理改写了请求区间
            long writeStart = start;
            long writeEnd = start + len - 1;
            String cr = resp.header(HttpUtil.HEAD_CONTENTRANGE);
            if(cr != null && !cr.isEmpty()) {
                long[] range = parseContentRange(cr);
                if(range == null) {
                    return new PartResult(RetCode.THIRD_PARTY_ERR, "invalid content-range:" + cr, true);
                }
                writeStart = range[0];
                writeEnd = range[1];
                if(writeStart > start || writeEnd < start + len - 1) {
                    return new PartResult(RetCode.THIRD_PARTY_ERR, "unexpected content-range:" + cr, true);
                }
            }

            int writeLen = (int)(writeEnd - writeStart + 1);
            try (InputStream in = resp.body()) {
                if(in == null) {
                    return new PartResult(RetCode.INTERNAL_ERROR, "invalid response,body is null", true);
                }
                synchronized(raf) {
                    raf.seek(writeStart);
                    int n = FileUtil.copyStream(in, writeLen, Channels.newOutputStream(raf.getChannel()));
                    if(n != writeLen) {
                        return new PartResult(RetCode.INTERNAL_ERROR,
                                "incomplete part:" + n + '/' + writeLen, true);
                    }
                }
                return PartResult.OK;
            } catch (IOException e) {
                LOG.error("Fail to save part {}-{} of `{}`", start, start + len - 1, req.objName, e);
                return new PartResult(RetCode.INTERNAL_ERROR, e.getMessage(), true);
            }
        }

        private PartResult saveWhole(HttpResp resp) {
            long n;
            try (InputStream in = resp.body()) {
                if(in == null) {
                    return new PartResult(RetCode.INTERNAL_ERROR, "invalid response,body is null", true);
                }
                synchronized(raf) {
                    raf.seek(0);
                    raf.setLength(0);
                    n = FileUtil.copyStream(in, Channels.newOutputStream(raf.getChannel()));
                }
            } catch (IOException e) {
                LOG.error("Fail to save whole object of `{}`", req.objName, e);
                return new PartResult(RetCode.INTERNAL_ERROR, e.getMessage(), true);
            }
            if(n != totalSize) {
                return new PartResult(RetCode.INTERNAL_ERROR, "incomplete body:" + n + '/' + totalSize, true);
            }
            wholeFile.set(true);
            if(LOG.isDebugEnabled()) {
                LOG.debug("Server ignored the Range header of `{}`,saved the whole object", req.objName);
            }
            return PartResult.OK;
        }
    }

    /** 单个分片的下载结果；retryable表示失败只是暂时的，可以重发该分片 */
    private static final class PartResult {
        static final PartResult OK = new PartResult(RetCode.OK, null, false);

        final int code;
        final String err;
        final boolean retryable;

        PartResult(int code, String err, boolean retryable) {
            this.code = code;
            this.err = err;
            this.retryable = retryable;
        }
    }

    /**
     * 解析响应头Content-Range：bytes start-end/total
     * @param value 响应头值
     * @return {start,end,total}，格式不对返回null
     */
    private static long[] parseContentRange(String value) {
        int sp = value.indexOf(' ');
        if(sp < 0) {
            return null;
        }
        String r = value.substring(sp + 1).trim();
        int slash = r.indexOf('/');
        int dash = r.indexOf('-');
        if(slash < 0 || dash < 0 || dash > slash) {
            return null;
        }
        long start = ValParser.parseLong(r.substring(0, dash).trim(), -1);
        long end = ValParser.parseLong(r.substring(dash + 1, slash).trim(), -1);
        long total = ValParser.parseLong(r.substring(slash + 1).trim(), -1);
        if(start < 0 || end < start || total < 0) {
            return null;
        }
        return new long[]{start, end, total};
    }

    /**
     * 计算分片大小：默认1MB；超大文件按最多1万片反算放大，保证不超过OSS的片数上限
     * @param size 文件大小
     * @return 分片大小
     */
    public static int partSize(long size) {
        long p = (size + 9999) / 10000;
        if(p < MULTIPART_PART_SIZE) {
            p = MULTIPART_PART_SIZE;
        }
        return p > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)p;
    }

    /**
     * 从预签名URL中解析对象名：去掉scheme、host与查询串，只保留路径部分。
     * @param url 预签名URL
     * @return 对象名；URL不以http/https开头或没有路径时返回null
     */
    public static String objectNameOf(String url) {
        if(url == null || url.isEmpty()) {
            return null;
        }
        int pos;
        if(url.startsWith("https://")) {
            pos = url.indexOf('/', "https://".length());
        } else if(url.startsWith("http://")) {
            pos = url.indexOf('/', "http://".length());
        } else {
            return null;
        }
        if(pos < 0) {
            return null;
        }
        String obj = url.substring(pos + 1);
        int q = obj.indexOf('?');
        if(q >= 0) {
            obj = obj.substring(0, q);
        }
        return obj.isEmpty() ? null : obj;
    }

    /** 取预签名URL的host，仅用于日志：双桶双写时能区分是哪个桶失败 */
    private static String hostOf(String url) {
        if(url == null) {
            return "";
        }
        int pos = url.indexOf("//");
        if(pos < 0) {
            return "";
        }
        int end = url.indexOf('/', pos + 2);
        return end < 0 ? url.substring(pos + 2) : url.substring(pos + 2, end);
    }

    /**
     * 解析XML标签内容：提取&lt;tag&gt;...&lt;/tag&gt;之间的文本。
     * @param xml XML文本
     * @param tag 标签名
     * @return 标签内容；xml为null或标签不存在时返回null
     */
    public static String tag(String xml, String tag) {
        if(xml == null || tag == null) {
            return null;
        }
        int b = xml.indexOf('<' + tag + '>');
        int e = xml.indexOf("</" + tag + '>');
        if(b < 0 || e < b) {
            return null;
        }
        return xml.substring(b + tag.length() + 2, e);
    }

    private static void closeQuietly(RandomAccessFile raf) {
        try {
            raf.close();
        } catch (IOException e) {
            //关闭失败不影响已经写入的内容
        }
    }

    /**
     * 分片上传。业务实例没有密钥，uploadId、各分片预签名URL、complete/abort预签名URL
     * 全部由根服务(company)签好下发，这里只负责：切片→逐片PUT→收集ETag→complete，失败则abort。
     * 每片都远小于单请求超时，慢链路上也不会整包重来。
     * @param plan 分片方案
     * @param req 整包PUT的请求信息（对象名、日志用）
     * @param locFile 本地文件
     * @return 执行结果
     */
    public static CompletableFuture<HandleResult> upload(MultipartPlan plan, UrlRequest req, File locFile) {
        long size = locFile.length();
        if(plan == null || plan.partSize <= 0 || size <= 0) {
            return upload(req, locFile); //没有可用的分片方案，退回整包PUT
        }
        int expect = (int)((size + plan.partSize - 1) / plan.partSize);
        if(plan.parts.size() != expect) { //方案与文件对不上，宁可整包PUT也不能拼出残缺对象
            LOG.warn("Multipart plan of `{}` has {}/{} parts,fall back to single put",
                    req.objName, plan.parts.size(), expect);
            return upload(req, locFile);
        }

        long startAt = System.currentTimeMillis();
        LOG.info("Upload `{}`({} bytes,{} parts,{} bytes/part) by multipart",
                req.objName, size, plan.parts.size(), plan.partSize);

        //同一个文件可能同时传多个桶，临时分片文件必须带唯一标记，否则互相覆盖
        String tag = Long.toHexString(System.nanoTime());
        //分片上传：各片分配到ossParallelParts条链，链间并行、链内串行。
        //链数必须保守，否则多zip双桶双写时并发连接过多，上行带宽被平分，
        //单片长时间发不完会被OSS/代理掐断；OSS按PartNumber组装对象，与上传先后无关。
        int lanes = Math.min(plan.parts.size(), parallelParts());
        List<CompletableFuture<HandleResult>> laneFutures = new ArrayList<>(lanes);
        for(int lane = 0; lane < lanes; lane++) {
            CompletableFuture<HandleResult> chain = CompletableFuture.completedFuture(HandleResult.OK);
            for(int i = lane; i < plan.parts.size(); i += lanes) {
                Part part = plan.parts.get(i);
                chain = chain.thenCompose(hr -> hr.code != RetCode.OK
                        ? CompletableFuture.completedFuture(hr)
                        : new PartUpload(plan, part, locFile, size, tag).start());
            }
            laneFutures.add(chain);
        }
        CompletableFuture<HandleResult> future = CompletableFuture
            .allOf(laneFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                for(CompletableFuture<HandleResult> f : laneFutures) {
                    HandleResult hr = f.join();
                    if(hr.code != RetCode.OK) {
                        return hr;
                    }
                }
                return HandleResult.OK;
            });

        return future.thenCompose(hr -> {
            if(hr.code != RetCode.OK) {
                abortMultipart(plan, hr.brief()); //不留半截分片在云端计费
                return CompletableFuture.completedFuture(hr);
            }
            return completeMultipart(plan);
        }).whenComplete((hr, ex) -> {
            if(hr == null || hr.code != RetCode.OK) {
                return;
            }
            if(LOG.isInfoEnabled()) {
                long useTime = Math.max(1, System.currentTimeMillis() - startAt);
                LOG.info("Upload `{}`({} bytes) by multipart done:use time={}ms,speed={}KB/s",
                        req.objName, size, useTime, (1000L * size / useTime / 1024));
            }
        });
    }

    /**
     * 分片并行上传的并发度：同时最多几条上传链，来自partition.cfg的ossParallelParts。
     * 【必须保守】现网备份会同时传多个zip且双桶双写，实际并发连接数 =
     * 并发zip数 × 桶数 × 本值。上行带宽被多条连接平分后，单片1MB可能几十秒都发不完，
     * OSS/代理会直接掐断连接（表现为"HTTP/1.1 header parser received no bytes"）。
     * 默认1即每个文件串行上传各片，但多个文件/桶之间仍然并行。
     */
    private static int parallelParts() {
        PartitionConfig pc = PartitionConfig.instance();
        int n = pc == null ? 1 : pc.ossParallelParts;
        return n < 1 ? 1 : n;
    }
    
    /**
     * 单片上传：把源文件的对应区间切到临时文件，再PUT这个临时文件。
     * 这样不用给HttpUtil加“按区间读文件”的BodyPublisher，代价是一片大小的临时磁盘占用。
     */
    private static final class PartUpload extends CompletableFuture<HandleResult> implements HttpCallback {
        private final MultipartPlan plan;
        private final Part part;
        private final File src;
        private final long offset;
        private final int len;
        private final String tag;
        private File partFile;
        private int retried;

        PartUpload(MultipartPlan plan, Part part, File src, long fileSize, String tag) {
            this.plan = plan;
            this.part = part;
            this.src = src;
            this.tag = tag;
            this.offset = (long)(part.number - 1) * plan.partSize;
            this.len = (int)Math.max(0, Math.min((long)plan.partSize, fileSize - offset));
        }

        CompletableFuture<HandleResult> start() {
            if(len <= 0) {
                complete(new HandleResult(RetCode.DATA_WRONG,
                        "invalid part " + part.number + " of `" + src.getName() + "`"));
                return this;
            }
            try {
                cutPart();
            } catch (IOException e) {
                LOG.error("Fail to cut part {} of `{}`", part.number, src, e);
                complete(new HandleResult(RetCode.INTERNAL_ERROR, "fail to read " + src));
                return this;
            }
            request();
            return this;
        }

        /** 把源文件的[offset,offset+len)切出来落成临时文件 */
        private void cutPart() throws IOException {
            partFile = new File(src.getAbsoluteFile().getParentFile(),
                    src.getName() + '.' + tag + ".part" + part.number);
            try(RandomAccessFile raf = new RandomAccessFile(src, "r");
                OutputStream out = new FileOutputStream(partFile)) {
                raf.seek(offset);
                byte[] buf = new byte[64 * 1024];
                int left = len;
                while(left > 0) {
                    int n = raf.read(buf, 0, Math.min(buf.length, left));
                    if(n < 0) {
                        throw new IOException("unexpected end of " + src);
                    }
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
        }

        private void request() {
            Map<String, String> headers = new HashMap<>();
            if(plan.headers != null) {
                headers.putAll(plan.headers);
            }
            String contentType = headers.get(HttpUtil.HEAD_CONTENT_TYPE);
            getHttpClient(predictedHttpTime(len)).asyncPut(part.url, headers, contentType, partFile, this);
        }

        @Override
        public void onResponse(HttpResp resp) {
            if(!resp.isSuccessful()) {
                String msg = errMsg(resp);
                int code = resp.code();
                //5xx/429是暂时的，4xx(如URL过期403)重试没有意义
                if((code >= 500 || code == 408 || code == 429) && retry(code + ":" + msg)) {
                    return;
                }
                LOG.error("upload part {} of `{}` failed,status code:{},{}", part.number, src.getName(), code, msg);
                deletePartFile();
                complete(new HandleResult(RetCode.THIRD_PARTY_ERR,
                        "fail to upload part " + part.number + "/" + plan.parts.size()));
                return;
            }

            String etag = resp.header("ETag");
            if(etag == null || etag.isEmpty()) {
                if(retry("no etag in response")) {
                    return;
                }
                deletePartFile();
                complete(new HandleResult(RetCode.THIRD_PARTY_ERR, "no etag of part " + part.number));
                return;
            }

            part.etag = etag;
            deletePartFile();
            complete(HandleResult.OK);
        }

        @Override
        public void onFailure(Throwable e, String url, String method) {
            if(retry(e.toString())) {
                return;
            }
            deletePartFile();
            String msg = e.getMessage() == null ? "internal exception" : e.getMessage();
            complete(new HandleResult(RetCode.INTERNAL_ERROR,
                    "fail to upload part " + part.number + "/" + plan.parts.size() + ":" + msg));
        }

        private boolean retry(String reason) {
            if(retried >= PART_RETRY) {
                return false;
            }
            retried++;
            LOG.warn("Retry part {} of `{}` after {}/{} from {}:{}", part.number, src.getName(),
                    retried, PART_RETRY, hostOf(part.url), reason);
            request();
            return true;
        }

        private void deletePartFile() {
            if(partFile != null) {
                FileUtil.remove(partFile);
            }
        }
    }

    /**
     * 合并分片。ETag按分片顺序拼成XML，POST到根服务预先签好的complete URL。
     * @param plan 分片方案
     * @return 执行结果
     */
    private static CompletableFuture<HandleResult> completeMultipart(MultipartPlan plan) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<CompleteMultipartUpload>");
        for(Part part : plan.parts) {
            if(part.etag == null) {
                return HandleResult.future(RetCode.INTERNAL_ERROR, "no etag of part " + part.number);
            }
            sb.append("<Part><PartNumber>").append(part.number)
              .append("</PartNumber><ETag>").append(part.etag).append("</ETag></Part>");
        }
        sb.append("</CompleteMultipartUpload>");

        String xml = sb.toString();
        Map<String, String> headers = new HashMap<>();
        if(plan.completeHeaders != null) {
            headers.putAll(plan.completeHeaders);
        }
        String contentType = headers.get(HttpUtil.HEAD_CONTENT_TYPE);
        UrlRequest req = new UrlRequest(plan.uploadId, "", "", plan.completeUrl, headers,
                System.currentTimeMillis(), 0);
        HttpUtil http = getHttpClient(predictedHttpTime(xml.length()));
        AbsObsCallback future = new AbsObsCallback(req) {
            @Override
            public HandleResult onSuccess(HttpResp resp) {
                String body = "";
                try {
                    body = resp.string();
                } catch (IOException e) {
                    LOG.warn("Fail to read complete response of {}", plan.uploadId, e);
                }
                if(!resp.isSuccessful() || body.startsWith("<Error")) { //OSS出错时也可能返回200+错误体
                    LOG.error("complete({}) failed,status code:{},{}", plan.uploadId, resp.code(), body);
                    return new HandleResult(RetCode.THIRD_PARTY_ERR,
                            "fail to complete multipart:" + (body.isEmpty() ? resp.code() : body));
                }
                return HandleResult.OK;
            }

            @Override
            protected void request() {
                http.asyncPost(plan.completeUrl, headers, contentType, xml, this);
            }
        };
        http.asyncPost(plan.completeUrl, headers, contentType, xml, future);
        return future;
    }

    /**
     * 放弃分片上传，清掉云端已上传的分片（异常中断时会残留，OSS仍会计费）。
     * @param plan 分片方案
     * @param reason 原因
     */
    private static void abortMultipart(MultipartPlan plan, String reason) {
        if(plan.abortUrl == null || plan.abortUrl.isEmpty()) {
            return;
        }
        LOG.warn("Abort multipart({}) of `{}` for {}", plan.uploadId, plan.objName, reason);
        getHttpClient(30000).asyncDelete(plan.abortUrl, new HashMap<>(), new HttpCallback() {
            @Override
            public void onResponse(HttpResp resp) {
                if(!resp.isSuccessful()) {
                    LOG.warn("Fail to abort multipart({}),status code:{}", plan.uploadId, resp.code());
                }
            }

            @Override
            public void onFailure(Throwable e, String url, String method) {
                LOG.warn("Fail to abort multipart({}):{}", plan.uploadId, e.toString());
            }
        });
    }
    
    public CompletableFuture<HandleResult> delete(UrlRequest req) {
        HttpUtil http = getHttpClient(10000);//ms
        AbsObsCallback future = new AbsObsCallback(req) {
            @Override
            public HandleResult onSuccess(HttpResp resp) {
                if (!resp.isSuccessful()) {
                    LOG.error("DELETE({}) failed, status code:{}", req.objName, resp.code());
                    return new HandleResult(RetCode.THIRD_PARTY_ERR, errMsg(resp));
                }

                if(LOG.isDebugEnabled()) {
                    long useTime = System.currentTimeMillis() - req.startAt;
                    LOG.debug("DELETE({}):use time={}", req.objName, useTime);
                }
                return HandleResult.OK;
            }

            @Override
            protected void request() {
                http.asyncDelete(req.url, req.headers, this);
            }
        };
        http.asyncDelete(req.url, req.headers, future);
        return future;
    }

    protected static String errMsg(HttpResp resp) {
        try {
            String s = resp.string();
            if(s.isEmpty()) {
                LOG.info("response body is empty");
                return "unknown error";
            }
            return s;
        } catch (IOException e) {
            LOG.error("Fail to get response of {}", resp.url(), e);
            return "unknown error";
        }
    }

    /**
     * 对象存储异步回调
     * @author flyinmind of csdn.net
     *
     */
    public abstract static class AbsObsCallback extends CompletableFuture<HandleResult> implements HttpCallback {
        private static final int MAX_RETRY = 2;
        private int retried = 0;
        protected final UrlRequest req;
        
        public abstract HandleResult onSuccess(HttpResp resp);
        /** 重新发起本次请求 */
        protected abstract void request();
        
        public AbsObsCallback(UrlRequest req) {
            this.req = req;
        }
        
        @Override
        public void onFailure(Throwable e, String url, String method) {
            if (retried < MAX_RETRY) {
                retried++;
                if(LOG.isDebugEnabled()) {
                    LOG.error("Retry {} of {} after the peer closed the connection:{}", retried, url, e);
                } else {
                    LOG.error("Retry {} of {} after the peer closed the connection:{}", retried, url, e.toString());
                }
                request();
                return;
            }
            if (LOG.isDebugEnabled()) {
                LOG.error("Fail to request({})", url, e);
            } else {
                LOG.error("Fail to request({}),{}", url, e.getMessage());
            }
            super.complete(new HandleResult(RetCode.INTERNAL_ERROR, "internal exception " + e.getMessage()));
        }

        @Override
        public void onResponse(HttpResp resp) {
            if(LOG.isDebugEnabled()) {
                LOG.debug("Call:{}, statusCode:{}", resp.url(), resp.code());
            }
            super.complete(onSuccess(resp));
        }
    }
    
    public static class UrlRequest {
        public final long startAt; //start time
        public final String objName;
        public final String url;
        public final String md5; //用于下载后验证内容完整性
        public final String pwd;
        public final int size;
        public final Map<String, String> headers;
        
        public UrlRequest(String objName, String md5, String pwd, String url,
                Map<String, String> headers, long startAt, int size) {
            this.objName = objName;
            this.url = url;
            this.headers = headers;
            this.startAt = startAt;
            this.md5 = md5;
            this.pwd = pwd;
            this.size = size;
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("obj", objName);
            map.put("url", url);
            map.put("md5", md5);
            map.put("pwd", pwd);
            map.put("size", size);
            map.put("headers", headers);
            return map;
        }
        
        public static UrlRequest fromMap(Map<String, Object> map) {
            String obj = ValParser.getAsStr(map, "obj");
            String url = ValParser.getAsStr(map, "url");
            String md5 = ValParser.getAsStr(map, "md5");
            String pwd = ValParser.getAsStr(map, "pwd");
            int size  = ValParser.getAsInt(map, "size");
            Map<String, Object> h = ValParser.getAsObject(map, "headers");

            Map<String, String> headers = h != null ? ValParser.toStrMap(h) : new HashMap<>();
            return new UrlRequest(obj, md5, pwd, url, headers,
                    System.currentTimeMillis(), size);
        }
    }
    
    /**
     * 分片上传方案。业务实例没有密钥，OSS的多段上传(initiate/uploadPart/complete)必须由
     * 根服务(company)用密钥签好预签名URL下发，实例照着执行即可。
     * <p>
     * 下发格式（放在/oss/dbputurl返回的urls数组每个元素的multipart字段里）：
     * <pre>
     * "multipart": {
     *   "uploadId": "OSS返回的uploadId",
     *   "partSize": 1048576,
     *   "headers": {"Content-Type": "application/octet-stream"},   // 每个分片PUT原样带上（Content-Type参与签名）
     *   "parts": [{"number": 1, "url": "预签名URL"}...],            // 按number升序，覆盖整个文件
     *   "completeUrl": "预签名 POST /obj?uploadId=...",             // body为CompleteMultipartUpload的XML
     *   "completeHeaders": {"Content-Type": "application/xml"},     // complete POST原样带上
     *   "abortUrl": "预签名 DELETE /obj?uploadId=..."
     * }
     * </pre>
     */
    public static final class MultipartPlan {
        public final String objName;
        public final String uploadId;
        public final int partSize;
        public final Map<String, String> headers;
        public final Map<String, String> completeHeaders;
        public final List<Part> parts;
        public final String completeUrl;
        public final String abortUrl;

        MultipartPlan(String objName, String uploadId, int partSize,
                Map<String, String> headers, Map<String, String> completeHeaders,
                List<Part> parts, String completeUrl, String abortUrl) {
            this.objName = objName;
            this.uploadId = uploadId;
            this.partSize = partSize;
            this.headers = headers;
            this.completeHeaders = completeHeaders;
            this.parts = parts;
            this.completeUrl = completeUrl;
            this.abortUrl = abortUrl;
        }

        /**
         * 解析根服务下发的分片方案
         * @param map multipart字段
         * @param objName 对象名，仅用于日志
         * @return 方案；字段不全或没有分片时返回null，调用方退回整包PUT
         */
        public static MultipartPlan fromMap(Map<String, Object> map, String objName) {
            if(map == null) {
                return null;
            }
            String uploadId = ValParser.getAsStr(map, "uploadId");
            String completeUrl = ValParser.getAsStr(map, "completeUrl");
            int partSize = ValParser.getAsInt(map, "partSize");
            List<Object> list = ValParser.getAsList(map, "parts");
            List<Part> parts = new ArrayList<>();
            if(list != null) {
                for(Object o : list) {
                    Map<String, Object> p = ValParser.parseObject(o);
                    String url = ValParser.getAsStr(p, "url");
                    if(url == null || url.isEmpty()) {
                        continue;
                    }
                    parts.add(new Part(ValParser.getAsInt(p, "number"), url));
                }
            }
            if(uploadId == null || uploadId.isEmpty() || completeUrl == null || completeUrl.isEmpty()
                    || partSize <= 0 || parts.isEmpty()) {
                return null;
            }
            parts.sort(Comparator.comparingInt(p -> p.number));

            Map<String, Object> h = ValParser.getAsObject(map, "headers");
            Map<String, Object> ch = ValParser.getAsObject(map, "completeHeaders");
            return new MultipartPlan(objName, uploadId, partSize,
                    h == null ? new HashMap<>() : ValParser.toStrMap(h),
                    ch == null ? new HashMap<>() : ValParser.toStrMap(ch),
                    parts, completeUrl, ValParser.getAsStr(map, "abortUrl"));
        }
    }

    /** 一个分片：预签名URL与上传后得到的ETag */
    public static final class Part {
        public final int number;
        public final String url;
        public String etag;

        public Part(int number, String url) {
            this.number = number;
            this.url = url;
        }
    }

    /**
     * 预测文件上传或下载所需时间
     * @param size 文件大小
     * @return 预测所需的毫秒数
     */
    public static int predictedHttpTime(long size) {
        long s = size / AVERAGE_SPEED; //seconds
        s /= 100;
        s *= 100; //100秒一个阶，避免出现过多的httpclient
        if(s < 300) {
            s = 300;
        }
        return (int)(s * 1000); //最大能支持170G文件，如此大的文件，已不建议使用本系统了
    }
}
