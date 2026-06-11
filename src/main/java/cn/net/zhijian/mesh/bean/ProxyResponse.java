package cn.net.zhijian.mesh.bean;

import java.util.Map;

/**
 * 用在ProxyBinaryCallback回调中
 */
public class ProxyResponse {
    public final Map<String, String> headers;
    public final byte[] body;
    
    public ProxyResponse(Map<String, String> headers, byte[] body) {
        this.headers = headers;
        this.body = body;
    }
}