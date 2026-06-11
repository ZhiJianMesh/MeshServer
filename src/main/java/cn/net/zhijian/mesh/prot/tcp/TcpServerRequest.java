package cn.net.zhijian.mesh.prot.tcp;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.IUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import io.netty.buffer.ByteBuf;

/**
 * TCP服务器中处理解析后请求体，请求由连接TCP服务器的端侧发起
 * 与处理http请求是一致的，只是报文发送使用TCP长连接，并且自己解析报文
 * @author flyinmind of csdn.net
 *
 */
public class TcpServerRequest extends AbsServerRequest {
    private static final Logger LOG = LogUtil.getInstance();

    private static final String LONGTCP1_0 = "tcp/1.0";
    private final TcpConnection conn;
    final int reqId; //请求序列号
    final int cmd; //请求序列号
    
    public TcpServerRequest(TcpConnection conn, int reqId, int cmd,
        String method, String uri, String protocol,
        Map<String, String> headers, Map<String, Object> params, byte[] body) {
        super(conn.ctx, method, uri, protocol, headers, params, body);
        this.conn = conn;
        this.reqId = reqId;
        this.cmd = cmd;
    }

    protected TcpServerRequest(TcpConnection conn, int reqId, int cmd,
            String method, String uri, String protocol,
            Map<String, String> headers, Map<String, Object> params,
            AccessToken token, ServiceInfo si, byte[] body, String traceId, int depth) {
        super(conn.ctx, method, uri, protocol, headers, params, body, traceId, depth);
        this.token = token;
        this.si = si;
        this.conn = conn;
        this.reqId = reqId;
        this.cmd = cmd;
    }
    
    @Override
    public AbsServerResponse createResponse() {
        return new TcpServerResponse(this.conn, this);
    }
    
    @Override
    public boolean isExternal() {
        if(this.reqNetType == RequestNetType.WAN) {
            return true;
        } 
        if(this.reqNetType == RequestNetType.LAN) {
            return false;
        }
        InetSocketAddress inetSocket = (InetSocketAddress)ctx.channel().remoteAddress();
        InetAddress inetAddr = inetSocket.getAddress();
        if(inetAddr.isSiteLocalAddress()
           || inetAddr.isLinkLocalAddress()
           || inetAddr.isLoopbackAddress()) { //请求来自内部
            this.reqNetType = RequestNetType.LAN;
            return false;
        }
        this.reqNetType = RequestNetType.WAN;
        return true;
    }

    @Override
    public AbsServerRequest copy(Map<String, Object> params) {
        return new TcpServerRequest(this.conn, this.reqId, this.cmd,
                this.method, this.uri, this.protocol, this.headers, params,
                this.token, this.si, this.body, this.traceId, this.depth);
    }

    /*
     * total_len(4，不包括len本身的4字节，LengthFieldBasedFrameDecoder已读取)
     * + ver(1) + cmd(1) + reqid(4)
     * + url_len(4,short) + url
     * + head_len(4) [+ headers]
     * + body_len(4) [+ body] 
     */
    public static TcpServerRequest create(TcpConnection conn, int cmd, int reqId, ByteBuf buf) {
        String method = cmdToMethod(cmd);
        int len = buf.readInt(); //url_len
        byte[] b = new byte[len];
        buf.readBytes(b);
        String uri = new String(b, IUtil.DEFAULT_CHARSET);
        
        Map<String, String> headers;
        len = buf.readInt(); //head_len
        if(len > 0) {
            b = new byte[len];
            buf.readBytes(b);
            headers = JsonUtil.jsonToStrMap(b);
        } else {
            headers = new HashMap<>();
        }
        if(LOG.isDebugEnabled()) {
            LOG.debug("TcpServerRequest,url:{}-{},headers:{}", method, uri, headers);
        }
        byte[] body = null;
        len = buf.readInt(); //body_len，通常是json
        if(len > 0) {
            body = new byte[len];
            buf.readBytes(body);
        }

        return new TcpServerRequest(conn, reqId, cmd, method, uri,
                LONGTCP1_0, headers, new HashMap<>(), body);
    }
    
    private static String cmdToMethod(int cmd) {
        if(cmd == ITcpProtocol.GET) {
            return IUtil.METHOD_GET;
        }
        if(cmd == ITcpProtocol.DELETE) {
            return IUtil.METHOD_DELETE;
        }
        if(cmd == ITcpProtocol.PUT) {
            return IUtil.METHOD_PUT;
        }
        return IUtil.METHOD_POST;
    }
    
    @Override
    public String remoteAddr() {
        return requestAddr();
    }
}
