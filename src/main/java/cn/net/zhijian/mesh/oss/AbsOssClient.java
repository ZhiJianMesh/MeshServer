package cn.net.zhijian.mesh.oss;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.HttpClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.HttpUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

public abstract class AbsOssClient extends HttpClient {
    //providers
    public static final String ALIOSS = "ALI"; //阿里云
    public static final String HWOSS = "HW"; //华为
    public static final String AWSOSS = "AWS"; //亚马逊
    public static final String TXOSS = "TX"; //腾讯

    protected static final String SCHEME = "https://";
    private static final int AVERAGE_SPEED = 60 * 1024;//按1秒60KB估计超时时间
    
    private static final Logger LOG = LogUtil.getInstance();

    protected final String keyId;
    protected final byte[] key;

    protected final String bucket;
    protected final String innerEndPoint;
    protected final String externalEndPoint;
    
    public AbsOssClient(String innerEndPoint, String externalEndPoint,
            String bucket, String keyId, String key) {
        this.innerEndPoint = innerEndPoint;
        this.externalEndPoint = externalEndPoint;
        this.bucket = bucket;
        this.keyId = keyId;
        this.key = key.getBytes(IConst.DEFAULT_CHARSET);
    }
    /**
     * 从对象存储中获得一个对象，并存如果saveAs不为空，则存入saveAs指定的文件中，
     * 否则存入HandleResult.data.content中，如果contentType不是text/xxx，则做base64编码后返回
     * @param endPoint 站点host
     * @param objName 对象名称
     * @param saveAs 保存为
     * @param contentType 类型
     * @return 处理结果
     */
    public abstract CompletableFuture<HandleResult> get(String endPoint, String objName, String saveAs, String contentType, int size);
    
    /**
     * 生成给第三方直接下载的url，只能使用外网
     * @param objName 对象名称
     * @param contentType 类型
     * @param contentMd5 校验码
     * @param pwd 对象密码
     * @param size 文件大小
     * @return 下载URL及头信息
     */
    public abstract UrlRequest getUrl(String objName, String contentType, String contentMd5, String pwd, int size);
    
    /**
     * 将本地文件存入对象存储
     * @param endPoint 站点host
     * @param locFile 本地文件
     * @param objName 对象名称
     * @param contentType 类型
     * @return 处理结果
     */
    public abstract CompletableFuture<HandleResult> put(String endPoint, File locFile, String objName, String contentType);

    /**
     * 生成给第三方直接上传的url
     * @param objName 对象名称
     * @param contentType 类型
     * @param contentMd5 校验码
     * @param size 大小
     * @param validTime url有效时间，以秒为单位
     * @return 上传URL及头信息
     */
    public abstract UrlRequest putUrl(String objName, String contentType, String contentMd5, String pwd, int size, int validTime);

    /**
     * 删除对象
     * @param endPoint 站点host
     * @param objName 对象名称
     * @return 处理结果
     */
    public abstract CompletableFuture<HandleResult> delete(String endPoint, String objName);
    
    
    public CompletableFuture<HandleResult> innerPut(File locFile, String objName, String contentType) {
        return put(innerEndPoint, locFile, objName, contentType);
    }
    
    public CompletableFuture<HandleResult> externalPut(File locFile, String objName, String contentType) {
        return put(externalEndPoint, locFile, objName, contentType);
    }
    
    public CompletableFuture<HandleResult> innerGet(String objName, String saveAs, String contentType, int size) {
        return get(innerEndPoint, objName, saveAs, contentType, size);
    }
    
    public CompletableFuture<HandleResult> externalGet(String objName, String saveAs, String contentType, int size) {
        return get(externalEndPoint, objName, saveAs, contentType, size);
    }
    
    public CompletableFuture<HandleResult> innerDelete(String objName) {
        return delete(innerEndPoint, objName);
    }
    
    public CompletableFuture<HandleResult> externalDelete(String objName) {
        return delete(externalEndPoint, objName);
    }
    
    public static CompletableFuture<HandleResult> upload(UrlRequest req, File locFile) {
        long size = locFile.length();
        AbsObsCallback future = new AbsObsCallback() {
            @Override
            public HandleResult onSuccess(Response resp) {
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
        };

        HttpUtil http = getHttpClient(predictedHttpTime(size)); //ms
        String contentType = req.headers.get(HttpUtil.HEAD_CONTENT_TYPE);
        http.asyncPut(req.url, req.headers, contentType, locFile, future);
        return future;
    }
    
    public static CompletableFuture<HandleResult> download(UrlRequest req, File saveAs, int size) {
        AbsObsCallback future = new AbsObsCallback() {
            @Override
            public HandleResult onSuccess(Response resp) {
                if (!resp.isSuccessful()) {
                    LOG.error("download({}) failed, status code:{}", req.url, resp.code());
                    return new HandleResult(RetCode.THIRD_PARTY_ERR, errMsg(resp));
                }

                try (ResponseBody body = resp.body()) {
                    if(body == null) {
                        return new HandleResult(RetCode.THIRD_PARTY_ERR, "invalid response,body is null");
                    }
                    long size = FileUtil.copyStream(body.byteStream(), saveAs);
                    if(LOG.isDebugEnabled()) {
                        long useTime = System.currentTimeMillis() - req.startAt;
                        LOG.debug("download({}):use time={},size={},speed={}", req.url, useTime, size, (1000L * size / useTime));
                    }
                    return HandleResult.OK;
                } catch (Exception e) {
                    LOG.error("Fail to get response from {}", resp.request().url(), e);
                    return HandleResult.OK;
                }
            }
        };
        
        HttpUtil http = getHttpClient(predictedHttpTime(size));//ms
        http.asyncGet(req.url, req.headers, future);
        return future;
    }
    
    
    protected static String errMsg(Response resp) {
        try (ResponseBody body = resp.body()) {
            if (body != null) {
                return body.string();
            } else {
                LOG.info("response body is empty");
                return "unknown error";
            }
        } catch (IOException e) {
            LOG.error("Fail to get response of {}", resp.request().url(), e);
            return "unknown error";
        }
    }

    /**
     * 对象存储异步回调
     * @author flyinmind of csdn.net
     *
     */
    public abstract static class AbsObsCallback extends CompletableFuture<HandleResult> implements Callback {
        public abstract HandleResult onSuccess(Response resp);

        @Override
        public void onFailure(Call call, IOException e) {
            if (LOG.isDebugEnabled()) {
                LOG.error("Fail to request({})", call.request().url(), e);
            } else {
                LOG.error("Fail to request({}),{}", call.request().url(), e.getMessage());
            }
            super.complete(new HandleResult(RetCode.INTERNAL_ERROR, "internal exception"));
        }

        @Override
        public void onResponse(Call call, Response resp) {
            if(LOG.isDebugEnabled()) {
                LOG.debug("Call:{}, statusCode:{}", call.request().url(), resp.code());
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
