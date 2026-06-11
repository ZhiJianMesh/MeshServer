package cn.net.zhijian.mesh.server;

import java.net.InetSocketAddress;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.ServerState;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.mesh.prot.TailExceptionHandler;
import cn.net.zhijian.mesh.prot.tcp.ITcpProtocol;
import cn.net.zhijian.mesh.prot.tcp.TcpConnection;
import cn.net.zhijian.mesh.prot.tcp.TcpServerRequest;
import cn.net.zhijian.mesh.server.TimerKeeper.TimerTaskWrapper;
import cn.net.zhijian.util.DateUtil.PeriodType;
import cn.net.zhijian.util.IUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.OptionalSslHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/**
 * TCP长连接服务
 * 运行在公有云，维持与客户端的长连接，有两个作用：
 * 1）接受端侧以TCP长连接方式连接服务器，并调用接口，端侧连接时需要做认证，
 *   在resourceChecker接口中实现；
 * 2）从普通端侧接受http请求，通过TCP长连接发送到TCP长连接客户端。
 */
public final class TcpChannel implements ITcpProtocol, IThreadPool {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String LONGLINK_HEARTBEAT_TIMER = "resource_health_checker_timer";
    private static final int HEARTBEAT_INTERVAL = 60 * 1000; //ms
    
    private static final AttributeKey<String> RESOURCE_KEY = AttributeKey.valueOf("rid"); //资源ID
    private final Map<String, TcpConnection> resources = new ConcurrentHashMap<>();
    public final ServerState state = new ServerState(ServerState.NULL);
    public final int port; // 连接主机端口
    private final IServiceServer realServer;
    private final ITcpResourceChecker resourceChecker;
    private final TailExceptionHandler tailExceptionHandler = new TailExceptionHandler();
    private Channel serverChannel = null;

    public TcpChannel(IServiceServer realServer, int port, ITcpResourceChecker resourceChecker) {
        this.port = port;
        this.realServer = realServer;
        this.resourceChecker = resourceChecker == null ? ITcpResourceChecker.DEFAULT : resourceChecker;
        this.state.state(ServerState.INITIALIZED);
    }

    /**
     * 创建TCP长连接并实例化channel
     * @param sslContext 服务端安全相关配置
     */
    public boolean start(SslContext sslContext) {
        if(this.serverChannel != null) {
            LOG.debug("Tcp long link server@{} already started", port);
            return true;
        }

        int cpuNum = Runtime.getRuntime().availableProcessors();
        LOG.debug("Start tcp long link server@{},cpuNum:{}", port, cpuNum);
        TimerKeeper.addTimerTask(new TimerTaskWrapper(
            LONGLINK_HEARTBEAT_TIMER, //长链接检测定时器
            PeriodType.CYCLE,
            HEARTBEAT_INTERVAL,
            new HealthChecker()
        ));
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(cpuNum > 6 ? 2 : 1, new IThreadPool.NamedThreadFactory("tcp_boss"));
        NioEventLoopGroup workerGroup = new NioEventLoopGroup(cpuNum, new IThreadPool.NamedThreadFactory("tcp_worker"));
        ServerBootstrap bootstrap = new ServerBootstrap(); // bootstrap,group等需要用局部变量，否则停止后，不能重启
        bootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) {
                    ChannelPipeline pl = ch.pipeline();
                    pl.addLast("ssl", new OptionalSslHandler(sslContext)); //可用TCP或SSL
                    pl.addLast("tcp_handler", new ServerHandler());
                    pl.addLast("last_exec", tailExceptionHandler);
                }
            }).option(ChannelOption.SO_BACKLOG, 128) // determining the number of resources queued
            .childOption(ChannelOption.SO_KEEPALIVE, Boolean.TRUE);

        LOG.info("Create tcp long link server@{}", port);
        try {
            ChannelFuture serverFuture = bootstrap.bind(port).sync();
            this.serverChannel = serverFuture.channel();
            this.serverChannel.closeFuture().addListener((ChannelFutureListener) future -> {
                LOG.info("Shutting down @{}", port);
                try {
                    bossGroup.shutdownGracefully(); // 先关boss
                    workerGroup.shutdownGracefully(); // 再关worker
                    LOG.info("Server@{} has been closed", port);
                } catch (Exception e) {
                    LOG.error("Fail to stop http server @{} ", port, e);
                }
            });
            return true;
        } catch (Exception e) {
            LOG.error("Fail to bind {}", port, e);
            this.state.state(ServerState.CLOSED);
            this.serverChannel = null;
            return false;
        }
    }
    
    public boolean stop() {
        if(this.serverChannel == null) {
            LOG.debug("Long link server has already been stoped");
            this.state.state(ServerState.CLOSED);
            return true;
        }
        
        this.state.state(ServerState.ALTERING);
        LOG.debug("Try to stop long link server");
        TimerKeeper.removeTimerTask(LONGLINK_HEARTBEAT_TIMER);
        try {
            closeAllConnections();
            return true;
        } catch(Exception e) {
            LOG.error("Fail to stop long link server {} ", this.port, e);
            return false;
        } finally {
            this.state.state(ServerState.CLOSED);
        }
    }
    
    /**
     * 关闭netty维护的tcp服务器
     */
    public synchronized void destroy() {
        stop();
        if(this.serverChannel != null) {
            try {
                this.serverChannel.close();
            } catch(Exception e) {
                LOG.error("Fail to stop tcp server@{}", port, e);
            } finally {
                this.serverChannel = null;
            }
        }
    }
    
    //@Sharable，LengthFieldBasedFrameDecoder有状态
    private class ServerHandler extends LengthFieldBasedFrameDecoder {
        public ServerHandler() {
            super(ByteOrder.BIG_ENDIAN, 10 * 1024 * 1024, 0, 4, 0, 4, true);
        }

        @Override
        protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
            // 在这里调用父类的方法,得到想要的部分
            ByteBuf content = (ByteBuf)super.decode(ctx, in);
            if (content != null) {
                Pool.execute(() -> {
                    try {
                        handle(ctx, content);
                    } catch(Exception e) {
                        LOG.error("Fail to handle response", e);
                    }
                });
            }
            return null; //没有后继处理器了
        }
        
        /*
         * total_len(4，不包括len本身的4字节，LengthFieldBasedFrameDecoder已读取)
         * + cmd(1) + reqid(4)
         * [+ url_len(2) + url]
         * + head_len(4) [+ headers]
         * + body_len(4，即使无body，也需要body_len) [+ body] 
         */
        private void handle(ChannelHandlerContext ctx, ByteBuf in) {
            int cmd = ((int)in.readByte()) & 0xff;
            int reqId = in.readInt();
            
            if(cmd == CONNECT) {//在connect之前，其他请求都是无效的，因为连接未记录
                handleConnect(ctx, in, reqId);
                return;
            }
            
            Attribute<String> attr = ctx.channel().attr(RESOURCE_KEY);
            String rid = attr.get();
            if(LOG.isDebugEnabled()) {
                LOG.debug("Request,command:{},readable:{},reqId:{},rid:{}",
                        Names.get(cmd), in.readableBytes(), reqId, rid);
            }
            
            TcpConnection con;
            if(StringUtil.isEmpty(rid) || (con = resources.get(rid)) == null) {
                LOG.warn("Fail to get resource({}) connection", rid);
                return;
            }
            
            switch(cmd) {
            case DISCONNECT:
                con.response(DISCONNECT, reqId, HttpResponseStatus.OK.code(), null, null);
                con.close();
                resources.remove(rid);
                break;
            case HEARTBEAT:
                con.response(HEARTBEAT, reqId, HttpResponseStatus.OK.code(), null, null);
                break;
            case GET:
            case POST:
            case PUT:
            case DELETE:
                CompletableFuture.runAsync(() -> {
                    AbsServerRequest req = TcpServerRequest.create(con, cmd, reqId, in);
                    if(!ChannelConfig.instance().wanAccessible) {
                        if(req.isExternal()) {
                            AbsServerResponse resp = req.createResponse();
                            resp.error(HttpResponseStatus.BAD_REQUEST, RetCode.INVALID_STATE);
                            LOG.error("Invalid call depth {} in `{}\t{}`", req.depth, req.uri, req.traceId);
                            return;
                        }
                    }
                    //只支持URL中带/api的接口调用，不支持文件请求
                    realServer.apiExecute(req);
                }, Pool);
                break;
            case CONTROL: //处理其他客户端通过http给长连接客户端发送的控制命令的响应
                con.handleCtrlResp(cmd, reqId, in);
                break;
            case CLOSE://服务端发出的关闭命令
                con.close();
                break;
            default:
                LOG.error("Invalid command:{}", cmd);
                break;
            }           
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
            LOG.error("Request triggered exception:{}", e.getMessage());
            if(ctx != null) {
                removeResource(ctx);
            }
        }
        
        // 客户端断开连接时的处理  
        @Override  
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {  
            super.channelInactive(ctx);
            removeResource(ctx);
        }
        
        private void removeResource(ChannelHandlerContext ctx) {
            Attribute<String> attr = ctx.channel().attr(RESOURCE_KEY);
            String rid = attr.get();
            TcpConnection conn = resources.get(rid);
            if(conn != null) {
                conn.close();
                resources.remove(rid);
                LOG.debug("Connection {} closed", rid);
            }            
        }
    }
    
    /**
     * 长连接客户端连接申请
     * len(4) + cmd(1) + reqId(4) + ver(4)
     * + resource_len + resource(resoure_name + : + access_code + @ + cid)
     * @param ctx 会话
     * @param in 请求内容
     * @param reqId 请求编号
     */
    private void handleConnect(ChannelHandlerContext ctx, ByteBuf in, int reqId) {
        int ver = in.readInt();
        int resourceLen = in.readInt();
        if(resourceLen > in.readableBytes()) {
            LOG.warn("Invalid resource length:{},readableBytes:{}", resourceLen, in.readableBytes());
            return;
        }
        byte[] buf = new byte[resourceLen];
        in.readBytes(buf);
        String res = new String(buf, IUtil.DEFAULT_CHARSET);
        int pos1 = res.lastIndexOf('@');//防止帐号密码中出现@，cid中是一个数字，一定不会有@
        if(pos1 <= 0) {
            LOG.warn("Invalid resource:{}", res);
            return;
        }
        String s = res.substring(pos1 + 1);
        int cid = StringUtil.parseInt(s, -1);
        int pos2 = res.indexOf(':');
        String resource = res.substring(0, pos2);
        String accessCode = res.substring(pos2 + 1, pos1);
        InetSocketAddress insocket = (InetSocketAddress)ctx.channel().remoteAddress();
        String ip = insocket.getAddress().getHostAddress();
        if(LOG.isDebugEnabled()) {
            LOG.debug("A connection request from {},resource:{}", ip, res);
        }

        resourceChecker.check(cid, resource, accessCode, ip).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to check resource({},{})", res, ip, e);
                ctx.close();
                return;
            }
            
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to check resource({},{}),result:{}", res, ip, hr.brief());
                ctx.close();
                return;
            }
            LOG.debug("Resource({}) connected", resource);
            Attribute<String> attr = ctx.channel().attr(RESOURCE_KEY);
            attr.set(resource);
            TcpConnection con = new TcpConnection(resource, ver, ctx);
            resources.put(resource, con);
            byte[] body = hr.toString().getBytes(IConst.DEFAULT_CHARSET);
            //响应中包括access_token,refresh_token,expires_at,token_type,id
            //端侧需要在expires_at(以毫秒为单位的unix时间戳)之前
            //使用refresh_token获得更新的access_token
            con.response(CONNECT, reqId, HttpResponseStatus.OK.code(), null, body);
        }, Pool);
    }

    private void closeAllConnections() {
        try {
            for(TcpConnection con : resources.values()) {
                con.close();
            }
            resources.clear();
        } catch(Exception e) {
            LOG.error("Fail to close all connection", e);
        }
    }
    
    /**
     * 给TCP长连接客户端发送控制命令，
     * 请求头中必须携带resource，标明请求发送的端侧
     * @param req 端侧请求体
     * @return 异步结果
     */
    public CompletableFuture<HandleResult> sendControl(AbsServerRequest req) {
        String rid = req.header(HEAD_RESOURCE);
        if(StringUtil.isEmpty(rid)) {
            return CompletableFuture.completedFuture(new HandleResult(RetCode.WRONG_PARAMETER, "no head " + HEAD_RESOURCE));
        }
        TcpConnection con = resources.get(rid);
        if(con == null) {
            return CompletableFuture.completedFuture(new HandleResult(RetCode.INVALID_NODE, "resource " + rid + " not online"));
        }
        //命令字，如果不传此头部，则默认为0
        int cmd = StringUtil.parseInt(req.header(HEAD_COMMAND), 0);

        return con.sendControl(req, cmd);
    }
    
    /**
     * 给TCP长连接客户端发送关闭连接命令，
     * @param req 端侧请求体
     * @return 异步结果
     */
    public CompletableFuture<HandleResult> sendClose(AbsServerRequest req) {
        String rid = req.header(HEAD_RESOURCE);
        if(StringUtil.isEmpty(rid)) {
            return CompletableFuture.completedFuture(new HandleResult(RetCode.WRONG_PARAMETER, "no head " + HEAD_RESOURCE));
        }
        TcpConnection con = resources.get(rid);
        if(con == null) {
            return CompletableFuture.completedFuture(new HandleResult(RetCode.INVALID_NODE, "resource " + rid + " not online"));
        }
        resources.remove(rid); //在此就删除，而不是收到响应时删除，防止客户端不给响应

        con.sendClose(60 * 5);
        return CompletableFuture.completedFuture(HandleResult.OK);

    }
    
    /**
     * 健康检查定时器
     * 用IdleStateHandler实现，即使指定时间内有消息交互，也会发出IdleStateEvent，
     * 所以弃用IdleStateHandler，改成定时器，每个心跳间隔检查一遍，
     * 应该比每个连接新建一个IdleStateHandler节省资源。
     * <p>
     * IdleStateHandler不起作用，可能是因为直接用ctx.writeAndFlush的原因，
     * 此函数直接返回消息，不逐个ChannelOutboundHandler过一遍。
     * 但是这些实现，跟ChannelInboundHandler没关系，所以不研究了。
     */
    private final class HealthChecker implements Runnable {
        private static final int MAX_IDLE_TIME = 3 * HEARTBEAT_INTERVAL;
        public void run() {
            TcpConnection conn;
            List<String> timeoutList = new ArrayList<>();
            for(Map.Entry<String, TcpConnection> o : resources.entrySet()) {
                conn = o.getValue();
                if(conn.idleTime() >= MAX_IDLE_TIME) {//长期无心跳
                    conn.close();
                    timeoutList.add(o.getKey());
                } else {
                    conn.checkRequests();
                }
            }

            for(String rid : timeoutList) {
                LOG.info("Resource {} removed", rid);
                resources.remove(rid);
            }
        }
    }
    
    /**
     * 检查资源是否可以接入
     */
    public interface ITcpResourceChecker {
        //默认使用用户服务验证，此类用户的type应为设备
        ITcpResourceChecker DEFAULT = (cid, resource, pwd, ip) -> {
            ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_USER)
               .url("/login")
               .cid(cid) //只能是当前服务器的公司id
               .traceId("devicelogin_" + ip)
               .put("account", resource)
               .put("password", pwd);
            return ServiceClient.servicePost(req);
        };
        
        CompletableFuture<HandleResult> check(int cid, String resource, String pwd, String ip);
    }
}
