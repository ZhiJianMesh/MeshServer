package cn.net.zhijian.mesh.prot.http;

import cn.net.zhijian.util.HttpUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.compression.CompressionOptions;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpResponse;


/**
 * 可以与ChunkedWriteHandler配合使用的HttpContentCompressor
 * 1）content-type不是指定的类型，也会放弃压缩；
 * 比如zip、docx、图片等本身已经压缩，再次压缩，即浪费了CPU，还会增加大小。
 * 2）太小的文件不压缩，小于2K的文件意义不是很大。
 * 
 * @author flyinmind of csdn.net
 *
 */
public class HttpChunkableContentCompressor extends HttpContentCompressor {
    private static final String[] COMPRESSABLE = new String[] {
        "/json", "text/plain", "/javascript", "text/css", "text/html", "text/csv"};

    public HttpChunkableContentCompressor(int min, CompressionOptions... compressionOptions) {
        super(min, compressionOptions);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        Object m = msg;
        if (msg instanceof ByteBuf) {
            /*
             * 将ByteBuf转换为HttpContent，
             * 使其能够使传递给HttpContentCompressor处理，
             * 否则，在有ChunkedWriteHandler时，无法压缩文件
             */
            ByteBuf buf = (ByteBuf) msg;
            
            if (buf.isReadable()) {
                //只编码非空缓冲区，因为空缓冲区可用于确定内容何时被刷新
                m = new DefaultHttpContent(buf);
            }
        }
        super.write(ctx, m, promise);
    }
    
    @Override
    protected Result beginEncode(HttpResponse headers, String acceptEncoding) throws Exception {
        String contentType = headers.headers().get(HttpUtil.HEAD_CONTENT_TYPE);
        char c = contentType.charAt(0);
        if(c == 't' || c == 'a') { //text or application
            for(String ct : COMPRESSABLE) {
                if(contentType.contains(ct)) {
                    return super.beginEncode(headers, acceptEncoding);
                }
            }
        }
        //Needn't, return null to disable compression
        return null;
    }
}
