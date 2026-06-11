package cn.net.zhijian.mesh.prot.tcp;

import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.BigFileReader;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.util.JsonUtil;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * http服务端响应体
 * @author flyinmind of csdn.net
 *
 */
class TcpServerResponse extends AbsServerResponse {
    private final TcpConnection conn;
    private final TcpServerRequest req;

    public TcpServerResponse(TcpConnection conn, TcpServerRequest req) {
        super(conn.ctx, req);
        this.conn = conn;
        this.req = req;
    }
    
    /**
     * 将响应内容写入channel
     * @param statusCode http状态码
     * @param resultCode 业务返回码
     */
    @Override
    public void error(HttpResponseStatus statusCode, int resultCode) {
        this.resultCode = resultCode;
        this.statusCode = statusCode;
        boolean channelActive = ctx.channel().isActive();
        if(channelActive) {
            conn.response(req.cmd, req.reqId, statusCode.code(), null, null);
        }
        log(channelActive);
    }
    
    @Override
    public void end(byte[] content, int offset, int len) {
        boolean channelActive = ctx.channel().isActive();
        if(channelActive) {
            byte[] headers = !this.headers.isEmpty() ? JsonUtil.objToBytes(this.headers) : null;
            conn.response(req.cmd, req.reqId, statusCode.code(), headers, content);
        }
        log(channelActive);
    }
    
    @Override
    public CompletableFuture<Boolean> sendChunkedFile(BigFileReader f) {
        throw new UnsupportedOperationException("sendChunkedFile not supported");
    }
    
    @Override
    public void end(ByteBuf body, int len) {
       throw new UnsupportedOperationException("end(ByteBuf body) not supported");
    }
}
