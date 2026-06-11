package cn.net.zhijian.mesh.frm.abs;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.BigFileReader;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.LogUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;

public abstract class AbsServerResponse {
    public static final String ACCESS_LOGGER_NAME = "ACCESS";
    private static final Logger LOG = LogUtil.getInstance();
    private static final Logger ACCESS = LogUtil.getInstance(ACCESS_LOGGER_NAME);
    public static final int CHUNK_SIZE = 16 * 1024;
    
    public final long start;
    protected final Map<String, String> headers = new HashMap<>();
    protected final AbsServerRequest req;
    protected final ChannelHandlerContext ctx;
    protected ServiceInfo si;

    protected HttpResponseStatus statusCode = HttpResponseStatus.OK;
    protected int resultCode = RetCode.OK;

    /**
     * 发送大文件
     * @param f 待发送文件，不能在外面用try-resource关闭raf，因为此读操作是异步的
     * @return 发送结果
     */
    public abstract CompletableFuture<Boolean> sendChunkedFile(BigFileReader f);
    public abstract void end(ByteBuf body, int len);
    public abstract void error(HttpResponseStatus statusCode, int resultCode);

    protected AbsServerResponse(ChannelHandlerContext ctx, AbsServerRequest req) {
        this.req = req;
        this.ctx = ctx;
        this.start = System.currentTimeMillis();
    }
    
    public void putHeader(String n, String v) {
        this.headers.put(n, v);
    }
    
    public void putHeaders(Map<String, String> headers) {
        this.headers.putAll(headers);
    }
    
    public Map<String, String> headers() {
        return headers;
    }
    
    protected void log(boolean channelActive) {
        int useTime = (int)(System.currentTimeMillis() - start);
        if(LOG.isDebugEnabled() && req.isApi) {
            LOG.debug("RESPONSE({})==Url:{},status:{},code:{},channelActive:{},useTime:{}",
                req.traceId, req.uri, this.statusCode,
                RetCode.name(this.resultCode), channelActive, useTime
            );
        }
        //method,useTime,statusCode,resultCode,uri,traceId,chanelState,addr
        ACCESS.info("R\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            req.method, useTime, this.statusCode, this.resultCode,
            req.uri, req.traceId, channelActive?1:0, req.remoteAddr()
        );
        if(si != null) {
            si.cpuTime += useTime;
        }
    }

    public void end() {
        log(isChannelActive());
    }

    public void end(HandleResult hr) {
        this.resultCode = hr.code;
        boolean channelActive = isChannelActive();
        if(channelActive) {
            String resultStr = hr.toString();
            byte[] content = resultStr.getBytes(IConst.DEFAULT_CHARSET);
            if(LOG.isDebugEnabled()) {
                LOG.debug("{}->result:{}", req.traceId, hr.toString(23));
            }
            end(content, 0, content.length);
        } else {
            log(false);
        }
    }
    
    /**
     * 结束响应处理，并将内容发送到ChannelHandlerContext中
     * @param content 内容
     * @param offset 偏移
     * @param len 长度
     */
    public void end(byte[] content, int offset, int len) {
        //Unpooled.wrappedBuffer将content数组包装成ByteBuf，包装过程中不会产生内存拷贝
        ByteBuf body = Unpooled.wrappedBuffer(content, offset, len);
        end(body, len);
    }

    public void setStatus(HttpResponseStatus status, int resultCode) {
        this.statusCode = status;
        this.resultCode = resultCode;
    }
    
    public void setServiceInfo(ServiceInfo si) {
        this.si = si;
    }
    
    public boolean isChannelActive() {
        return ctx.channel().isActive();
    }
}
