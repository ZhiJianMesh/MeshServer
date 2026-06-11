package cn.net.zhijian.mesh.prot.http;

import java.util.HashMap;
import java.util.Map;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.ssl.ApplicationProtocolNames;

/**
 * 解析后的http服务端请求体
 * @author flyinmind of csdn.net
 *
 */
public class Http2ServerRequest extends Http1ServerRequest {
    private final Http2FrameStream stream; //唯一标识一个请求，本质就是一个整型id
    
    public Http2ServerRequest(ChannelHandlerContext ctx,
        String method, String uri, String protocol,
        Map<String, String> headers,
        Map<String, Object> params, byte[] body, Http2FrameStream stream) {
        super(ctx, method, uri, protocol, headers, params, body);
        this.stream = stream;
    }
    
    public static AbsServerRequest create(ChannelHandlerContext ctx,
            Http2HeadersFrame headerFrame, ByteBuf data) {
        Map<String, Object> params = new HashMap<>();
        Http2Headers http2Headers = headerFrame.headers();
        Map<String, String> headers = parseHeaders(http2Headers);
        String method = http2Headers.method().toString().toUpperCase();
        Http2FrameStream stream = headerFrame.stream();
        byte[] body = null;
        if(data != null) {
            body = new byte[data.readableBytes()];
            data.readBytes(body);
        }
        
        return new Http2ServerRequest(ctx, method,
                http2Headers.path().toString(),
                ApplicationProtocolNames.HTTP_2, headers,
                params, body, stream);
    }

    protected Http2ServerRequest(ChannelHandlerContext ctx,
            String method, String uri, String protocol,
            Map<String, String> headers, Map<String, Object> params,
            AccessToken token, ServiceInfo si, byte[] body,
            Http2FrameStream stream, String traceId, int depth) {
        super(ctx, method, uri, protocol, headers, params, token, si, body, traceId, depth);
        this.stream = stream;
    }
    
    /**
     * 只是更换掉参数，其他都保持不变。用于复制请求
     * @param params 请求参数
     * @return 请求对象
     */
    @Override
    public Http2ServerRequest copy(Map<String, Object> params) {
        return new Http2ServerRequest(this.ctx, this.method, this.uri,
                this.protocol, this.headers, params, this.token,
                this.si, this.body, this.stream, this.traceId, this.depth);
    }

    @Override
    public AbsServerResponse createResponse() {
        return new Http2ServerResponse(this.ctx, this, this.stream);
    }
    
    private static Map<String, String> parseHeaders(Http2Headers hh) {
        Map<String, String> headers = new HashMap<>();
        for(Map.Entry<CharSequence, CharSequence> n : hh) {
            headers.put(n.getKey().toString().toLowerCase(), n.getValue().toString());
        }
        return headers;
    }
}
