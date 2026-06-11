package cn.net.zhijian.mesh.oss;

import static cn.net.zhijian.util.HttpUtil.HEAD_AUTH;
import static cn.net.zhijian.util.HttpUtil.HEAD_CONTENT_LENGTH;
import static cn.net.zhijian.util.HttpUtil.HEAD_CONTENT_MD5;
import static cn.net.zhijian.util.HttpUtil.HEAD_CONTENT_TYPE;
import static cn.net.zhijian.util.HttpUtil.HEAD_DATE;
import static cn.net.zhijian.util.HttpUtil.HEAD_HOST;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.HttpUtil;
import cn.net.zhijian.util.IUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.UrlPathInfo;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class AliOssClient extends AbsOssClient {
    private static final Logger LOG = LogUtil.getInstance();
    private final String authHeader;
    
    /**
     * 在阿里云OSS管理台`访问控制 RAM`中设置，创建一个access key
     * @param innerEndPoint 内网接入点，
     *  <a href="https://help.aliyun.com/document_detail/31834.html?spm=a2c4g.98985.0.0.763c7041nF4SQv">阿里云OSS存储内网接入点</a>
     * @param externalEndPoint 外网接入点
     * @param bucket 桶名称
     * @param keyId 密钥id
     * @param key 密钥
     */
    public AliOssClient(String innerEndPoint, String externalEndPoint,
            String bucket, String keyId, String key) {
        super(innerEndPoint, externalEndPoint, bucket, keyId, key);
        this.authHeader = "OSS " + keyId + ':';
    }
    
    @Override
    public CompletableFuture<HandleResult> get(String endPoint, String objName, String saveAs, String contentType, int size) {
        long reqAt = System.currentTimeMillis();
        String date = DateUtil.gmtDate(reqAt);

        //METHOD\ncontent-md5\ncontent-type\ndate\n/bucket/objName
        String signData = "GET\n\n" + contentType + '\n' + date
                + "\n/" + bucket + '/' + objName;
        byte[] sign = SecureUtil.hmacSHA1(signData.getBytes(IConst.DEFAULT_CHARSET), key);
        String auth = ByteUtil.stdBase64Encode(sign);
        Map<String, String> headers = new HashMap<>();
        headers.put(HEAD_HOST, endPoint);
        headers.put(HEAD_CONTENT_TYPE, contentType);
        headers.put(HEAD_DATE, date);
        headers.put(HEAD_AUTH, authHeader + auth);
        
        if(LOG.isDebugEnabled()) {
            LOG.debug("Headers:{}, SignData:{}", headers, signData);
        }

        String url = SCHEME +  endPoint + '/' + objName;
        AbsObsCallback future = new AbsObsCallback() {
            @Override
            public HandleResult onSuccess(Response resp) {
                if (!resp.isSuccessful()) {
                    LOG.error("GET({}) failed, status code:{}", objName, resp.code());
                    return new HandleResult(RetCode.THIRD_PARTY_ERR, errMsg(resp));
                }
                
                try (ResponseBody body = resp.body()) {
                    if(body == null) {
                        return new HandleResult(RetCode.THIRD_PARTY_ERR, "invalid response,body is null");
                    }
                    long size = FileUtil.copyStream(body.byteStream(), new File(saveAs));
                    if(LOG.isDebugEnabled()) {
                        long useTime = System.currentTimeMillis() - reqAt;
                        LOG.debug("GET({}):use time={},size={},speed={}", objName, useTime, size, (1000L * size / useTime));
                    }
                    return HandleResult.OK;
                } catch (Exception e) {
                    LOG.error("Fail to get response from {}", resp.request().url(), e);
                    return HandleResult.OK;
                }
            }
        };
        HttpUtil http = getHttpClient(predictedHttpTime(size));
        http.asyncGet(url, headers, future);
        return future;
    }

    @Override
    public UrlRequest getUrl(String objName, String contentType,
            String contentMd5, String pwd, int size) {
        long reqAt = System.currentTimeMillis();
        String date = DateUtil.gmtDate(reqAt);
        int validTime = predictedHttpTime(size); //ms,按80k每秒估计
        int expiresAt = (int)((reqAt + validTime) / 1000);

        //METHOD\ncontent-md5\ncontent-type\nexpiresAt\n/bucket/objName
        String signData = "GET\n\n" + contentType + '\n' + expiresAt
                + "\n/" + bucket + '/' + objName;

        byte[] sign = SecureUtil.hmacSHA1(signData.getBytes(IUtil.DEFAULT_CHARSET), key);
        String auth = ByteUtil.stdBase64Encode(sign);
        Map<String, String> headers = new HashMap<>();
        headers.put(HEAD_HOST, externalEndPoint);
        headers.put(HEAD_CONTENT_TYPE, contentType);
        headers.put(HEAD_DATE, date);
        headers.put("response-content-type", contentType);
        
        if(LOG.isDebugEnabled()) {
            LOG.debug("Headers:{}, SignData:{}", headers, signData);
        }

        String url = SCHEME + externalEndPoint + "/" + objName
                + "?OSSAccessKeyId=" + keyId + "&Expires=" + expiresAt
                + "&Signature=" + UrlPathInfo.urlEncode(auth);

        return new UrlRequest(objName, contentMd5, pwd, url, headers, reqAt, size);
    }

    @Override
    public CompletableFuture<HandleResult> put(String endPoint, File locFileName, String objName, String contentType) {
        long reqAt = System.currentTimeMillis();
        String date = DateUtil.gmtDate(reqAt);
        int size = (int)locFileName.length();
        String md5 = FileUtil.digest(locFileName);

        //METHOD\ncontent-md5\ncontent-type\nDATE\n/bucket/objName
        String signData = "PUT\n" + md5 + '\n' + contentType + '\n'
                + date + "\n/" + bucket + '/' + objName;

        byte[] sign = SecureUtil.hmacSHA1(signData.getBytes(IUtil.DEFAULT_CHARSET), key);
        String auth = ByteUtil.stdBase64Encode(sign);
        Map<String, String> headers = new HashMap<>();
        headers.put(HEAD_HOST, endPoint);
        headers.put(HEAD_DATE, date);
        headers.put(HEAD_CONTENT_MD5, md5);
        headers.put(HEAD_CONTENT_TYPE, contentType);
        headers.put(HEAD_CONTENT_LENGTH, Integer.toString(size));
        headers.put(HEAD_AUTH, authHeader + auth);
        headers.put("Cache-Control", "no-cache");

        if(LOG.isDebugEnabled()) {
            LOG.debug("Headers:{}, SignData:{}", headers, signData);
        }
        String url = SCHEME + endPoint + '/' + objName;
        AbsObsCallback future = new AbsObsCallback() {
            @Override
            public HandleResult onSuccess(Response resp) {
                if (!resp.isSuccessful()) {
                    LOG.error("PUT({}) failed, status code:{}", objName, resp.code());
                    return new HandleResult(RetCode.THIRD_PARTY_ERR, errMsg(resp));
                }

                if(LOG.isDebugEnabled()) {
                    long useTime = System.currentTimeMillis() - reqAt;
                    LOG.debug("PUT({}):use time={},size={},speed={}", objName, useTime, size, (1000L * size / useTime));
                }
                return HandleResult.OK;
            }
        };
        http.asyncPut(url, headers, contentType, locFileName, future);
        return future;
    }

    @Override
    public UrlRequest putUrl(String objName, String contentType,
            String contentMd5, String pwd, int size, int validTime) {
        long reqAt = System.currentTimeMillis();
        String date = DateUtil.gmtDate(reqAt);
        int expiresAt = validTime + (int)(reqAt / 1000);

        //METHOD\ncontent-md5\ncontent-type\nexpiresAt\n/bucket/objName
        String signData = "PUT\n" + contentMd5 + '\n' + contentType + '\n'
            + expiresAt + "\n/" + bucket + '/' + objName;

        byte[] sign = SecureUtil.hmacSHA1(signData.getBytes(IUtil.DEFAULT_CHARSET), key);
        String auth = ByteUtil.stdBase64Encode(sign);
        Map<String, String> headers = new HashMap<>();
        headers.put(HEAD_HOST, externalEndPoint);
        headers.put(HEAD_DATE, date);
        headers.put(HEAD_CONTENT_MD5, contentMd5);
        headers.put(HEAD_CONTENT_TYPE, contentType);
        headers.put(HEAD_CONTENT_LENGTH, Integer.toString(size));
        headers.put("Cache-Control", "no-cache");
        
        String url = SCHEME + externalEndPoint + "/" + objName
                + "?OSSAccessKeyId=" + keyId + "&Expires=" + expiresAt
                + "&Signature=" + UrlPathInfo.urlEncode(auth);
        if(LOG.isDebugEnabled()) {
            LOG.debug("Headers:{}, SignData:{}", headers, signData);
        }
        return new UrlRequest(objName, contentMd5, pwd, url, headers, reqAt, size);
    }

    @Override
    public CompletableFuture<HandleResult> delete(String endPoint, String objName) {
        long reqAt = System.currentTimeMillis();
        String date = DateUtil.gmtDate(reqAt);

        //METHOD\ncontent-md5\ncontent-type\nDate\n/bucket/objName
        String signData = "DELETE\n\n\n" + date + "\n/" + bucket + '/' + objName;

        byte[] sign = SecureUtil.hmacSHA1(signData.getBytes(IUtil.DEFAULT_CHARSET), key);
        String auth = ByteUtil.stdBase64Encode(sign);
        Map<String, String> headers = new HashMap<>();
        headers.put(HEAD_HOST, endPoint);
        headers.put(HEAD_DATE, date);
        headers.put(HEAD_AUTH, authHeader + auth);

        if(LOG.isDebugEnabled()) {
            LOG.debug("Headers:{}, SignData:{}", headers, signData);
        }
        AbsObsCallback future = new AbsObsCallback() {
            @Override
            public HandleResult onSuccess(Response resp) {
                if (!resp.isSuccessful()) {
                    LOG.error("DELETE({}) failed, status code:{}", objName, resp.code());
                    return new HandleResult(RetCode.THIRD_PARTY_ERR, errMsg(resp));
                }

                if(LOG.isDebugEnabled()) {
                    long useTime = System.currentTimeMillis() - reqAt;
                    LOG.debug("DELETE({}):use time={}", objName, useTime);
                }
                return HandleResult.OK;
            }
        };
        http.asyncDelete(SCHEME + endPoint + '/' + objName, headers, future);
        return future;
    }
}
