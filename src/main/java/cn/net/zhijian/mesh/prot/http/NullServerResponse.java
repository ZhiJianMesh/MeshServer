package cn.net.zhijian.mesh.prot.http;

import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.BigFileReader;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * http服务端响应体
 * @author flyinmind of csdn.net
 *
 */
public class NullServerResponse extends AbsServerResponse {
    public NullServerResponse(AbsServerRequest req) {
        super(null, req);
    }
    
    @Override
    public CompletableFuture<Boolean> sendChunkedFile(BigFileReader f) {
        return CompletableFuture.completedFuture(true);
    }    
    
    /**
     * 将响应内容写入channel
     * @param statusCode http状态码
     * @param resultCode 业务返回码
     */
    @Override
    public void error(HttpResponseStatus statusCode, int resultCode) {
    }
    
    @Override
    public void end(ByteBuf body, int len) {
    }
    
    @Override
    public void end(HandleResult hr) {
    }
    
    @Override
    public boolean isChannelActive() {
        return false;
    }
}
