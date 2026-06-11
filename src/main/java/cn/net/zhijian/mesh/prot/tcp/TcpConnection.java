package cn.net.zhijian.mesh.prot.tcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * 保存长连接客户端与服务器之间的长连接信息
 * @author flyinmind of csdn.net
 */
public final class TcpConnection implements ITcpProtocol, IThreadPool {
    private static final Logger LOG = LogUtil.getInstance();

    public static final int NULL_REQ_ID = 0;
    private static final int REQ_TIMEOUT = 10 * 1000;
    //服务端发给客户端报文的编号，与客户端发给服务端报文的编号一样，但是客户端的在客户端维护。
    //一个连接中唯一，到顶后溢出，值回到0，只需要保证在id回头时，早期的请求已处理
    //如果一天回头一次，每秒可以发送24万次
    private final AtomicInteger Sequence = new AtomicInteger(NULL_REQ_ID);
    
    final ChannelHandlerContext ctx; //与私有云服务端的连接
    public final String rid;
    public final int ver; //连接使用的协议版本，用于以后可能的版本兼容
    private final Map<Integer, ControlRequest> requests = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile long recentMsg = -1;
    
    public TcpConnection(String rid, int ver, ChannelHandlerContext ctx) {
        this.ctx = ctx;
        this.rid = rid;
        this.ver = ver;
    }
    
    /**
     * 服务端发给客户端的控制命令报文
     * @param req 请求体
     * @param cmd 命令字
     * @return 结果
     */
    public CompletableFuture<HandleResult> sendControl(AbsServerRequest req, int cmd) {
        if(ctx == null || !ctx.channel().isActive()) {
            return CompletableFuture.completedFuture(new HandleResult(RetCode.NO_RIGHT, "channel is not active"));
        }

        Map<String, String> h = req.headers();
        byte[] headers = h != null && !h.isEmpty() ? JsonUtil.objToBytes(h) : null;
        
        Map<String, Object> params = req.params();
        byte[] body = params != null && !params.isEmpty() ? JsonUtil.objToBytes(params) : null;
        int reqId = sendControl(CONTROL, cmd, headers, body);
        CompletableFuture<HandleResult> future = new CompletableFuture<>();
        requests.put(reqId, new ControlRequest(future, req));
        
        return future; //等待handleCtrlResp处理完毕再complete
    }
    
    public void close() {
        if(ctx == null || !ctx.channel().isActive()) {
            return;
        }
        LOG.debug("Connection({}) closed, idle time:{}", rid, idleTime());
        ctx.channel().close();
        requests.clear();
    }
    
    /**
     * 服务端发给客户端的控制命令报文
     * @param cmd 命令字，CLOSE或CONTROL
     * @param val 附加值，cmd为CLOSE、CONTROL时定义不同
     * @param headers 请求头，
     * @param body 请求体
     */
    private int sendControl(int cmd, int val, byte[] headers, byte[] body) {
        int reqId = Sequence.getAndIncrement();
        if(reqId <= 0) { //0为保留值，用在服务端发起的CLOSE命令中，见sendClose()的实现
            Sequence.set(1);
            reqId = Sequence.getAndIncrement();
        }
        /*
         * total_len(4) + cmd(1) + reqid(4)
         * + val(4，子命令字，由系统自己定义)
         * + head_len(4) [+ headers]
         * + body_len(4) [+ body] 
         * len的值不包括len本身的4字节
         */
        int len = 1/*cmd*/ + 4 + 4
                + 4 + (headers != null ? headers.length : 0)
                + 4 + (body != null ? body.length : 0);

        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(len);
        buf.writeInt(len);
        buf.writeByte(cmd);
        buf.writeInt(reqId);
        buf.writeInt(val);
        if(headers != null) {
            buf.writeInt(headers.length);
            buf.writeBytes(headers);
        } else {
            buf.writeInt(0);
        }
        
        if(body != null) {
            buf.writeInt(body.length);
            buf.writeBytes(body);
        } else {
            buf.writeInt(0);
        }    
        ctx.writeAndFlush(buf);
        
        return reqId;
    }
    
    /**
     * 服务端发起的关闭命令
     */
    public void sendClose(int tryAfter) {
        /*
         * total_len(4) + cmd(1) + reqid(4) + val(4，method)
         * + head_len(4) [+ headers]
         * + body_len(4) [+ body] 
         * len的值不包括len本身的4字节
         */
        int len = 17; //1/*cmd*/ + 4 + 4 + 4 + 0 + 4 + 0

        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(len);
        buf.writeInt(len);
        buf.writeByte(CLOSE);
        buf.writeInt(NULL_REQ_ID); //reqId，close消息不会从http客户端发起
        buf.writeInt(tryAfter); //val，要求客户端至少tryAfter秒后再连
        buf.writeInt(0); //head_len
        buf.writeInt(0); //body_len
 
        ctx.writeAndFlush(buf);
    }
    
    /**
     * 处理完客户端请求后，响应内容给客户端
     * @param cmd 命令字，原样返回
     * @param reqId 请求ID，原样返回
     * @param statusCode 状态码，与http状态码一致
     * @param headers 响应头，可以为空
     * @param body 响应体，可以为空
     */
    public void response(int cmd, int reqId, int statusCode, byte[] headers, byte[] body) {
        recentMsg = System.currentTimeMillis();
        if(LOG.isDebugEnabled()) {
            LOG.debug("Response,command:{},reqId:{},status_code:{}",
                    Names.get(cmd), reqId, statusCode);
        }
        /*
         * total_len(4) + cmd(1) + reqid(4)
         * + statusCode(4)
         * + head_len(4) [+ headers]
         * + body_len(4) [+ body] 
         * len的值不包括len本身的4字节
         */
        int bodyLen = body != null ? body.length : 0;
        int len = 1/*cmd*/ + 4 + 4
                + 4 + (headers != null ? headers.length : 0)
                + 4 + bodyLen;
        
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(len);
        buf.writeInt(len);
        buf.writeByte(cmd);
        buf.writeInt(reqId);
        buf.writeInt(statusCode);
        if(headers != null) {
            buf.writeInt(headers.length);
            buf.writeBytes(headers);
        } else {
            buf.writeInt(0);
        }
        
        if(body != null) {
            buf.writeInt(bodyLen);
            buf.writeBytes(body);
        } else {
            buf.writeInt(0);
        }
        ctx.writeAndFlush(buf);
    }
    
    /**
     * 当其他客户端使用http发送了请求之后，服务器转给了长连接客户端，
     * 当长连接客户端处理完成后，服务器再异步返回给请求客户端
     * @param cmd 命令，只有CONTROL与CLOSE
     * @param in 请求报文
     * @return 是否处理成功
     */
    public boolean handleCtrlResp(int cmd, int reqId, ByteBuf in) {
        recentMsg = System.currentTimeMillis();
        /*
         * total_len(4，LengthFieldBasedFrameDecoder已经去除了头部total_len)
         * + cmd(1，外部处理时已读取)
         * + reqid(4，外部处理时已读取)
         * + statusCode(4)
         * + body_len(4) [+ body] //没有heads部分
         * len的值不包括len本身的4字节
         */
        int statusCode = in.readInt();
        if(LOG.isDebugEnabled()) {
            LOG.debug("Status code:{},reqId:{},company:{},cmd:{}",
                    statusCode, reqId, rid, Names.get(cmd));
        }
        
        ControlRequest req = requests.get(reqId);
        if(req == null) {
            LOG.error("No control request in the connection({}),reqid:{}", rid, reqId);
            return false;
        }

        if(statusCode != HttpResponseStatus.OK.code()) {
            req.future.complete(new HandleResult(RetCode.API_ERROR, "status not ok:" + statusCode));
            return false;
        }
        int len = in.readInt(); //head_len，如果无header，需返回0
        if(len > 0) { //即使发送了headers，也会被忽略，headers部分，完全可以放到body中
            in.skipBytes(len);
        }
        len = in.readInt(); //body_len,如果无body，需返回0
        if(len > 0) {
            byte[] body = new byte[len];
            in.readBytes(body);
            Map<String, Object> data = JsonUtil.jsonToMap(body);
            req.future.complete(new HandleResult(RetCode.OK, data));
        } else {
            req.future.complete(HandleResult.OK);
        }
        requests.remove(reqId);
        return true;
    }
    
    public int idleTime() {
        /*
         * 心跳检查时，执行一次过期request清理，
         * 心跳检查周期较长，所以执行时，request在端侧早已过期，
         * 这里只做内存释放
         */
        return (int)(System.currentTimeMillis() - recentMsg);
    }
    
    public void checkRequests() {
        long cur = System.currentTimeMillis();
        //entrySet与values相比，性能上有极其微小的优势
        Set<Entry<Integer, ControlRequest>> list = requests.entrySet();
        if(list.isEmpty() || cur - list.iterator().next().getValue().req.reqTime < REQ_TIMEOUT) {
            return; //如果最早插入的还没有过期，则全部没有过期，绝大部分情况在此结束
        }
        
        Pool.execute(() -> {
            ControlRequest req;
            List<Integer> timeoutList = new ArrayList<>();
            HandleResult timeout = new HandleResult(RetCode.SYSTEM_TIMEOUT);
            for(Map.Entry<Integer, ControlRequest> r : list) {
                req = r.getValue();
                //requests按插入顺序从前到后排列，所以，只要出现未超时的，后面的都不会超时
                if(cur - req.req.reqTime < REQ_TIMEOUT) {
                    break;
                }
                req.future.complete(timeout);
                timeoutList.add(r.getKey());
            }
            
            for(int reqId : timeoutList) {
                requests.remove(reqId);
            }
        });
    }
    
    private static class ControlRequest {
        final CompletableFuture<HandleResult> future;
        final AbsServerRequest req;
        
        ControlRequest(CompletableFuture<HandleResult> future, AbsServerRequest req) {
            this.future = future;
            this.req = req;
        }
    }
}
