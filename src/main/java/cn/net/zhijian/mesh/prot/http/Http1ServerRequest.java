package cn.net.zhijian.mesh.prot.http;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.IPUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.ssl.ApplicationProtocolNames;

/**
 * 解析后的http服务器端请求体
 * @author flyinmind of csdn.net
 *
 */
public class Http1ServerRequest extends AbsServerRequest {
    private static final String[] HEADER_X_FORWARD = new String[] {
        "x-forwarded-for", "x-real-ip", "http_client_ip", "http_x_forwarded_for"
    };

    public Http1ServerRequest(ChannelHandlerContext ctx,
        String method, String uri, String protocol,
        Map<String, String> headers, Map<String, Object> params, byte[] body) {
        super(ctx, method, uri, protocol, headers, params, body);
    }

    protected Http1ServerRequest(ChannelHandlerContext ctx,
            String method, String uri, String protocol,
            Map<String, String> headers, Map<String, Object> params,
            AccessToken token, ServiceInfo si, byte[] body, String traceId, int depth) {
        super(ctx, method, uri, protocol, headers, params, body, traceId, depth);
        this.token = token;
        this.si = si;
    }
    
    public static AbsServerRequest create(ChannelHandlerContext ctx, FullHttpRequest req) {
        Map<String, Object> params = new HashMap<>();
        Map<String, String> headers = parseHeaders(req.headers());
        byte[] body = null;
        String method = req.method().toString().toUpperCase();
        
        if(IConst.METHOD_POST.equals(method) || IConst.METHOD_PUT.equals(method)) {
            /* 放在线程池外面先获取body，如果放到线程池中，
             * 在本函数中body的ByteBuf就已经释放，
             * 在线程池中，再去获取body，就会出现内存异常
             * 只接受json格式的请求，不适合做大文件上传操作
             */
            ByteBuf buf = req.content();
            body = new byte[buf.readableBytes()];
            buf.readBytes(body);
        }
        return new Http1ServerRequest(ctx, method, req.uri(),
                ApplicationProtocolNames.HTTP_1_1, headers, params, body);
    }

    /**
     * 只是更换掉参数，其他都保持不变。用于复制请求
     * @param params 请求参数
     * @return 请求对象
     */
    @Override
    public Http1ServerRequest copy(Map<String, Object> params) {
        return new Http1ServerRequest(this.ctx,this.method, this.uri,
                this.protocol, this.headers, params,
                this.token, this.si, this.body, this.traceId, this.depth);
    }

    @Override
    public AbsServerResponse createResponse() {
        return new Http1ServerResponse(this.ctx, this);
    }
    
    @Override
    public boolean isExternal() {
        if(this.reqNetType == RequestNetType.WAN) {
            return true;
        } 
        if(this.reqNetType == RequestNetType.LAN) {
            return false;
        }
        InetSocketAddress inetSocket = (InetSocketAddress) ctx.channel().remoteAddress();
        if(isFromLan(inetSocket, headers)) { //请求来自内部
            this.reqNetType = RequestNetType.LAN;
            return false;
        }
        this.reqNetType = RequestNetType.WAN;
        return true;
    }

    private static Map<String, String> parseHeaders(HttpHeaders hh) {
        Map<String, String> headers = new HashMap<>();
        for(Map.Entry<String, String> n : hh) {
            headers.put(n.getKey().toLowerCase(), n.getValue());
        }
        return headers;
    }

    /**
     * 获得请求侧IP，先从请求头中找，找不到，则直接返回源IP。
     * 在过代理后，源IP会发生变化，所以必须先从请求头中找
     * @return IP地址
     */
    @Override
    public String remoteAddr() {
        for(String n : HEADER_X_FORWARD) {
            String s = headers.get(n); //要求headers在录入时，全部转小写
            if(s == null || s.isEmpty()) {
                continue;
            }
            int pos = s.indexOf(';'); //有多个的情况，用分号分隔，第一个就是最初的IP
            if(pos > 0) {
                return s.substring(pos);
            }
            return s;
        }
        return requestAddr();
    }
    
    /**
     * 判断请求是否来自外网
     * @return 如果是，则返回true
     */
    public static boolean isFromLan(InetSocketAddress inetSocket, Map<String, String> headers) {
        InetAddress inetAddr = inetSocket.getAddress();
        if(inetAddr.isLoopbackAddress()) { //环回地址，自己请求自己
            return true;
        }

        int p1, p2;
        String s;
        for(String n : HEADER_X_FORWARD) {
            s = headers.get(n); //headers在录入时，全部转小写
            if(s == null || s.isEmpty()) {
                continue;
            }
            //只要有一个是外网地址，则认为请求来自外网
            for(p1 = 0; (p2 = s.indexOf(';', p1)) > 0; p1 = p2 + 1) {
                if(!IPUtil.isLanIp(s.substring(p1, p2))) {
                    return false;
                }
            }
            if(p1 < s.length() - 1 && !IPUtil.isLanIp(s.substring(p1))) {
                return false;
            }
        }
        
        //如果源ip是内网IP，则一定来内网，
        //如果请求经过SLB，且SLB未加x-forward头，走到此处，判断会有误
        return inetAddr.isSiteLocalAddress() || inetAddr.isLinkLocalAddress();
    }
}
