package cn.net.zhijian.mesh.prot.http;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.BigFileReader;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.util.HttpUtil;
import cn.net.zhijian.util.LogUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.concurrent.GenericFutureListener;

/**
 * http服务端响应体
 * @author flyinmind of csdn.net
 *
 */
class Http1ServerResponse extends AbsServerResponse {
    private static final Logger LOG = LogUtil.getInstance();

    public Http1ServerResponse(ChannelHandlerContext ctx, AbsServerRequest req) {
        super(ctx, req);
    }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public CompletableFuture<Boolean> sendChunkedFile(BigFileReader f) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            HttpHeaders headers = response.headers();

            for(Map.Entry<String, String> i : this.headers.entrySet()) {
                headers.add(i.getKey(), i.getValue());
            }

            headers.add(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            headers.set(HttpHeaderNames.CONTENT_LENGTH, f.size);
            //headers.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);

            ctx.write(response);
            ChunkedFile chunkedFile = new ChunkedFile(f.file(), 0, f.size, CHUNK_SIZE);
            ctx.writeAndFlush(chunkedFile, ctx.newProgressivePromise());
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
               //不能使用ChannelProgressiveFutureListener
               //因为传递的是GenericFutureListener
               .addListener((GenericFutureListener) future -> {
                   result.complete(true);
                   end(); //写日志
                   //LOG.debug("File.http1 {} transfer completed,size:{}", f.file(), f.size);
               });
        } catch (FileNotFoundException e) {
            LOG.warn("File {} not found", f);
            error(HttpResponseStatus.NOT_FOUND, RetCode.API_NOT_FOUND);
            result.complete(false);
        } catch (IOException e) {
            LOG.warn("Fail to read file {}", f, e);
            error(HttpResponseStatus.INTERNAL_SERVER_ERROR, RetCode.INTERNAL_ERROR);
            result.complete(false);
        } catch(Exception e) {
            LOG.warn("Fail to send file {}", f, e);
            error(HttpResponseStatus.INTERNAL_SERVER_ERROR, RetCode.INTERNAL_ERROR);
            result.complete(false);            
        }
        return result;
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
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, this.statusCode);
            HttpHeaders hh = response.headers();
            hh.add(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            for(Map.Entry<String, String> i : headers.entrySet()) {
                hh.add(i.getKey(), i.getValue());
            }
            hh.add(HttpHeaderNames.CONTENT_LENGTH, 0); //如果不设置长度，会导致客户端一直等待
            ctx.writeAndFlush(response);
        }
        log(channelActive);
    }
    
    @Override
    public void end(ByteBuf body, int len) {
        boolean channelActive = ctx.channel().isActive();
        if(channelActive) {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, this.statusCode, body);
            HttpHeaders hh = response.headers();
            hh.add(HttpUtil.HEAD_CONTENT_LENGTH, len);
            hh.add(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            for(Map.Entry<String, String> i : headers.entrySet()) {
                hh.add(i.getKey(), i.getValue());
            }
            ctx.writeAndFlush(response);
        }
        log(channelActive);
    }
}
