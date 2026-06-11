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
import cn.net.zhijian.util.LogUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.concurrent.GenericFutureListener;

class Http2ServerResponse extends AbsServerResponse {
    private static final Logger LOG = LogUtil.getInstance();
    
    private final Http2FrameStream stream;
    
    protected Http2ServerResponse(ChannelHandlerContext ctx, AbsServerRequest req, Http2FrameStream stream) {
        super(ctx, req);
        this.stream = stream;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public CompletableFuture<Boolean> sendChunkedFile(BigFileReader f) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            Http2Headers headers = new DefaultHttp2Headers().status(HttpResponseStatus.OK.codeAsText());
            headers.setLong(HttpHeaderNames.CONTENT_LENGTH, f.size);
            //headers.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            
            for(Map.Entry<String, String> i : this.headers.entrySet()) {
                headers.add(i.getKey(), i.getValue());
            }

            // Write the initial headers
            ctx.write(new DefaultHttp2HeadersFrame(headers).stream(stream));

            //不能使用以下方法，否则客户端会一直堵塞到超时为止
            //Http2DataChunkedInput chunkedInput = new Http2DataChunkedInput(new ChunkedFile(f.file(), 0, f.size, CHUNK_SIZE), stream);
            //ctx.writeAndFlush(chunkedInput, ctx.newProgressivePromise());

            //分块发送文件数据，http2本身是分片发送的，所以直接分片输出即可
            long position = 0;
            int readLen;
            ByteBuf data;
            byte[] buffer = new byte[CHUNK_SIZE];
            while(position < f.size) {
                readLen = f.read(position, buffer);
                data = ctx.alloc().buffer(readLen);
                data.writeBytes(buffer, 0, readLen);
                ctx.write(new DefaultHttp2DataFrame(data, false).stream(stream));
                position += readLen;
            }
            // 发送结束帧
            //不能使用ChannelProgressiveFutureListener
            //因为传递的是GenericFutureListener
            ctx.writeAndFlush(new DefaultHttp2DataFrame(true).stream(stream))
               .addListener((GenericFutureListener) future -> {
                   result.complete(true);
                   end(); //写日志
                   //LOG.debug("File.http2 {} transfer completed,size:{}", f.file(), f.size);
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

    @Override
    public void end(ByteBuf body, int len) {
        boolean channelActive = ctx.channel().isActive();
        if(channelActive) {
            Http2Headers headers = new DefaultHttp2Headers().status(HttpResponseStatus.OK.codeAsText());
            for(Map.Entry<String, String> e : this.headers.entrySet()) {
                headers.set(e.getKey(), e.getValue());
            }
            headers.setInt(HttpHeaderNames.CONTENT_LENGTH, len);
            ctx.write(new DefaultHttp2HeadersFrame(headers).stream(stream));
            ctx.writeAndFlush(new DefaultHttp2DataFrame(body, true).stream(stream));
        }
        log(channelActive);
    }

    @Override
    public void error(HttpResponseStatus statusCode, int resultCode) {
        this.resultCode = resultCode;
        this.statusCode = statusCode;
        boolean channelActive = ctx.channel().isActive();
        if(channelActive) {
            Http2Headers headers = new DefaultHttp2Headers().status(statusCode.codeAsText());
            for(Map.Entry<String, String> i : this.headers.entrySet()) {
                headers.add(i.getKey(), i.getValue());
            }
            headers.setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true).stream(stream));
        }
        log(channelActive);
    }
}
