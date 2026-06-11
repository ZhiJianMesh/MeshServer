package cn.net.zhijian.mesh.server;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.ServerState;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.prot.TailExceptionHandler;
import cn.net.zhijian.mesh.prot.http.Http1ServerRequest;
import cn.net.zhijian.mesh.prot.http.Http2ServerRequest;
import cn.net.zhijian.mesh.prot.http.HttpChunkableContentCompressor;
import cn.net.zhijian.util.LogUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.OptionalSslHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;

/**
 * HTTP Server封装，当前实现使用netty，可以根据需要替换为其他实现
 * @author flyinmind of csdn.net
 * 内存优化记录：
 * 内存占用分析：
 * 1)不加载service，共104M；加载service，不加载ansj，共160M；
 * 2)全加载，总共320M。
 * 主要的内存消耗在ansj的词库加载；
 * 优化措施：
 * 1)使用自定义的分词工具，用HashSet实现，总共215M内存，节省了105M内存，分词1000次，约210ms，略逊于ansj；
 * 2)使用自实现的Hash+二字与三子词首字母分类，总共175M内存，节省了145M，约310ms；
 * 3)使用自实现的Hash，总共180M内存，节省140M，查询性能约200ms，比HashSet略优。
 * 以上优化实现剔除了ansj，自实现分词能力(在StringSpliter中)
 */
public final class HttpChannel {
    private static final Logger LOG = LogUtil.getInstance();

    private static final int MAX_DEPTH = 20;
    private static final int MAX_CONTENT_LENGTH = 64 * 1024 * 1024;
    private static final int MIN_COMPRESS_LENGTH = 2 * 1024;
    private static final int NETTY_BUF_SIZE = 128 * 1024;

    private final IServiceServer realServer;
    public final ServerState state = new ServerState(ServerState.NULL); //用在js查看状态，并无其他用途
    private final Http1ServerHandler http1ServerHandler = new Http1ServerHandler(); //无状态，所以可以共享
    private final TailExceptionHandler tailExceptionHandler = new TailExceptionHandler();
    
    private final boolean alpnSupported; //是否支持http2协商
    private final boolean compressSupported;

    public final int port;

    private Channel serverChannel = null;

    public HttpChannel(IServiceServer realServer, int port) {
        this.realServer = realServer;
        this.state.state(ServerState.INITIALIZED);
        this.port = port;
        this.alpnSupported = AbsPlatform.alpnSupported();
        this.compressSupported = AbsPlatform.compressSupported();
    }

    /**
     * 启动服务
     * @param sslContext 安全会话，用于建立https连接
     * @return 是否成功
     */
    public synchronized boolean start(SslContext sslContext) {
        if(this.serverChannel != null) {
            LOG.debug("Server is already running, need not start again");
            return true;
        }
        int cpuNum = Runtime.getRuntime().availableProcessors();
        LOG.info("Server state {}, cpuNum:{}", state.state(), cpuNum);
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(cpuNum > 6 ? 2 : 1, new IThreadPool.NamedThreadFactory("netty_boss")); //处理accept，不必太多线程
        NioEventLoopGroup workerGroup = new NioEventLoopGroup(cpuNum, new IThreadPool.NamedThreadFactory("netty_worker"));
        ServerBootstrap bootstrap = new ServerBootstrap(); //bootstrap,group等需要用局部变量，否则停止后，不能重启
        bootstrap.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            //.handler(new LoggingHandler(LogLevel.WARN)) //打印handler的处理过程，没用
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) {
                    ChannelPipeline pl = ch.pipeline();
                    if(alpnSupported) { //支持协商，则使用http2，即使是http也使用http2
                        pl.addLast("optional_ssl", new MeshOptionalSslHandler(sslContext));
                    } else {
                        pl.addLast("optional_ssl", new OptionalSslHandler(sslContext));
                        setHttp1Handlers(pl);
                    }
                }
            }).option(ChannelOption.SO_BACKLOG, 128) // determining the number of connections queued
            .childOption(ChannelOption.SO_SNDBUF, NETTY_BUF_SIZE)  //设置收发缓冲区大小
            .childOption(ChannelOption.SO_RCVBUF, NETTY_BUF_SIZE)
            .childOption(ChannelOption.SO_KEEPALIVE, Boolean.TRUE);

        LOG.info("Create http server({}), state:{}", port, this.state);
        try {
            ChannelFuture serverFuture = bootstrap.bind(port).sync();
            this.serverChannel = serverFuture.channel();
            this.serverChannel.closeFuture().addListener((ChannelFutureListener) future -> {
                LOG.info("Shutting down {}", port);
                try {
                    bossGroup.shutdownGracefully(); //先关boss
                    workerGroup.shutdownGracefully(); //再关worker
                    LOG.info("Server {} has been closed", port);
                } catch (Exception e) {
                    LOG.error("Fail to stop http server {} ", port, e);
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

    private void setHttp1Handlers(ChannelPipeline pl) {
        pl.addLast("http1_codec", new HttpServerCodec());

        if(compressSupported) {
            //compressor必须放在aggregator前面，否则将无法访问
            pl.addLast("compressor", new HttpChunkableContentCompressor(MIN_COMPRESS_LENGTH));
        }
        pl.addLast("aggregator", new HttpObjectAggregator(MAX_CONTENT_LENGTH));
        //提供chunk方式文件下载，比如DownloadLog中
        pl.addLast("chunked_writer", new ChunkedWriteHandler());
        pl.addLast("http1_handler", http1ServerHandler);
        pl.addLast("tail_exec", tailExceptionHandler);
    }

    /**
     * 根据请求是否是https，来判断是否启用SslContext。
     * 且，如果是https，则启用http2方式，否则仍然使用http1.1方式。
     * 处理http协议的请求，只用http1.1，不做协商，因为只有在浏览器中才会使用http1.1，
     * 而浏览器在处理http请求时，都没有携带http2的PRE头，无法做ClearText协商。
     * 服务端的服务间调用都使用https，做alpn协商，与http无关。所以最终放弃ClearText协商。
     * @see <a href="https://www.baeldung.com/netty-http2">HTTP/2 in Netty</a>
     * 此连接中内容有所帮助，但是body读取部分也有巨大的误导。
     * http2的body部分必须自己拼接后才可以使用。
     * @author flyinmind of csdn.net
     *
     */
    //@Sharable，基于ByteToMessageDecoder，不可共享
    private class MeshOptionalSslHandler extends OptionalSslHandler {
        public MeshOptionalSslHandler(SslContext sslContext) {
            super(sslContext);
        }

        @Override
        protected SslHandler newSslHandler(ChannelHandlerContext ctx, SslContext sslContext) {
            //创建SSL会话前，将http2的处理放到pipeline的末尾
            ctx.pipeline().addLast(new Http2OrHttpHandler());
            return sslContext.newHandler(ctx.alloc());
        }

        @Override
        protected ChannelHandler newNonSslHandler(ChannelHandlerContext ctx) {
            setHttp1Handlers(ctx.pipeline());
            return null;
        }

        @Override
        protected String newSslHandlerName() {
            return "mesh_ssl";
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
            //super.exceptionCaught(ctx, e);
            //LOG.warn("MeshOptionalSslHandler triggered exception:{}", e.getMessage());
            if(ctx != null && ctx.channel().isActive()) {
                ctx.close();
            }
        }
    }
    
    @Sharable
    private final class Http2OrHttpHandler extends ApplicationProtocolNegotiationHandler {
        Http2OrHttpHandler() {
            super(ApplicationProtocolNames.HTTP_1_1);
        }

        @Override
        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
            ChannelPipeline pl = ctx.pipeline();
            //不支持CleartextHttp2ServerUpgrade
            if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                Http2FrameCodecBuilder builder = Http2FrameCodecBuilder.forServer()
                        .initialSettings(Http2Settings.defaultSettings())
                        .autoAckSettingsFrame(true)
                        .decoupleCloseAndGoAway(true)
                        .autoAckPingFrame(true);
                
                pl.addLast("http2_codec", builder.build());
                if(compressSupported) {
                    pl.addLast("compressor", new HttpChunkableContentCompressor(MIN_COMPRESS_LENGTH));
                }
                //h2的情况，无需聚集，在Http2ServerHandler中由H2Request完成
                //pl.addLast("aggregator", new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                //http2中，不必使用以下方法提供chunk方式文件下载，比如DownloadLog中
                //pl.addLast("chunked_writer", new ChunkedWriteHandler());
                pl.addLast("http2_handler", new Http2MultiplexHandler(new Http2ServerHandler()));
                pl.addLast("tail_exec", tailExceptionHandler);
                //pl.addLast("discard_all", discardAllHandler); //减少DEBUG日志中不必要的打印
            } else {
                LOG.debug("configurePipeline http1 after ALPN negotiation");
                setHttp1Handlers(pl); //其余的都使用http1.1
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
            LOG.warn("Http2OrHttpHandler triggered exception:{}", e.getMessage());
            if(ctx != null && ctx.channel().isActive()) {
                ctx.close();
            }
        }
    }
    
    private void handleRequest(AbsServerRequest req) {
        if(req.depth > MAX_DEPTH) { //有跟踪的情况下，才可以阻止超长调用链
            AbsServerResponse resp = req.createResponse();
            resp.error(HttpResponseStatus.BAD_REQUEST, RetCode.INVALID_STATE);
            LOG.error("Invalid call depth {} in `{}\t{}`", req.depth, req.uri, req.traceId);
            return;
        }

        if(!ChannelConfig.instance().wanAccessible) {
            if(req.isExternal()) {
                AbsServerResponse resp = req.createResponse();
                resp.error(HttpResponseStatus.BAD_REQUEST, RetCode.INVALID_STATE);
                LOG.error("Invalid call from {} to `{}\t{}`", req.remoteAddr(), req.uri, req.traceId);
                return;
            }
        }

        CompletableFuture.runAsync(() -> {
            if (req.isApi) { //url必须带/api/前缀
                realServer.apiExecute(req);
            }else { //其他静态文件一律存在/file目录下，但是url不必带/file/前缀
                realServer.fileExecute(req);
            }
        }, IThreadPool.Pool);
    }

    /**
     * 与http1.1的主要区别在于传输方式不同，
     * HTTP1是将http消息体完整解析完之后，一并发送给handler，
     * 而HTTP2总体上将消息分成header与body两部分，body部分又分成多个msg发送，
     * 需要业务自己拼接后才可以解析HTTP。
     * http2的每个消息都携带了streamId，标识消息属于哪一个请求，响应时也需要streamId。
     * streamid在每个链接中是唯一的，但是不同的链接不唯一
     * 一个TCP链接中可以同时传递多个流，每个流对应一个独立的http请求
     * 与http1.1的链接共享不同，1.1一个链接中，同一时间只能有一个http请求
     * @author flyinmind of csdn.net
     *
     */
    @Sharable
    private final class Http2ServerHandler extends ChannelDuplexHandler {
        //缓存http2的请求报文，拼接完整后，再给后端处理
        //同一个链接中的id是唯一的
        private final Map<Integer, FullHttp2Request> requests = new ConcurrentHashMap<>();
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            //只有HEADERS与DATA报文，其他在Http2FrameCodec中已处理
            //比如RESET/GOAWAY/WINDOW_UPDATE等
            if (msg instanceof Http2HeadersFrame) {
                Http2HeadersFrame frame = (Http2HeadersFrame)msg;
                if (frame.isEndStream()) { //只有请求头，无请求体，比如GET/DELETE请求
                    handleRequest(Http2ServerRequest.create(ctx, frame, null));
                } else {
                    requests.put(frame.stream().id(), new FullHttp2Request(frame));
                }
            } else if (msg instanceof Http2DataFrame) {
                Http2DataFrame data = (Http2DataFrame)msg;
                Http2FrameStream stream = data.stream();
                int streamId = stream.id();
                FullHttp2Request h2Req = requests.get(streamId);
                if(h2Req != null) {
                    h2Req.writeBody(data.content());
                    if (data.isEndStream()) {
                        handleRequest(Http2ServerRequest.create(ctx, h2Req.headFrame, h2Req.body));
                        h2Req.release();
                        requests.remove(streamId); //处理完之后，要释放占用的空间
                    }
                }
                /* 
                 * 直接使用本类处理完data，需要更新window frame，增加处理过的Data大小，不知道啥用处
                 * 使用Http2MultiplexHandler时，则不必处理，
                 * 否则报“Attempting to return too many bytes for stream”错误
                 */
                //ctx.write(new DefaultHttp2WindowUpdateFrame(data.initialFlowControlledBytes()).stream(stream));
                data.release();
            } else {
                /*
                 * 不释放，就会放到TailContext中处理，
                 * 主要有DefaultHttp2SettingsAckFrame等
                 * 功能上没有任何问题，如果开启debug打印，会有很多提醒日志
                 * Discarded inbound message、Discarded message pipeline 
                 * super.channelRead(ctx, msg)还是会向TailContext走
                 */
                ReferenceCountUtil.release(msg);
            }
        }
        
        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            //无论内容是否完整，收到每个片段都会触发channelReadComplete
            //LOG.debug("channelReadComplete");
            super.channelReadComplete(ctx);
            ctx.channel().flush();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
            //LOG.warn("Http2ServerHandler triggered exception:{}", e.getMessage());
            if(ctx != null && ctx.channel().isActive()) {
                ctx.close();
            }
        }
    }

    @Sharable
    private final class Http1ServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            AbsServerRequest req = Http1ServerRequest.create(ctx, request);
            handleRequest(req);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            //LOG.debug("channelReadComplete");
            super.channelReadComplete(ctx);
            ctx.flush(); //必须添加flush，否则可能会报异常
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
            //LOG.warn("Http1ServerHandler triggered exception:{}", e.getMessage());
            if(ctx != null && ctx.channel().isActive()) {
                ctx.close();
            }
        }
    }
    
    private static final class FullHttp2Request {
        private final Http2HeadersFrame headFrame;
        private final ByteBuf body = ByteBufAllocator.DEFAULT.buffer(1024, 64 * 1024 * 1024); //最大64M

        public FullHttp2Request(Http2HeadersFrame frame) {
            this.headFrame = frame;
        }
        
        public void writeBody(ByteBuf body) {
            this.body.writeBytes(body);
        }
        
        public void release() {
            this.body.release();
        }
    }

    /**
     * 停止接口的处理，并没有停止netty
     * @return 结果
     */
    public synchronized boolean stop() {
        if(!state.isRunning()) {
            LOG.debug("Server is not running, need not stop");
            return true;
        }

        state.state(ServerState.ALTERING);
        LOG.info("Stop http server {}", port);
        try {
            //必须先调用destroy，然后再停止netty，因为在destroy中可能会调用接口
            realServer.destroy();
        } catch(Exception e) {
            LOG.error("Fail to stop http server {}", port, e);
            return false;
        } finally {
            state.state(ServerState.CLOSED);
        }
        return true;
    }

    /**
     * 关闭netty维护的http服务器
     */
    public synchronized void destroy() {
        stop();
        if(this.serverChannel != null) {
            try {
                this.serverChannel.close();
            } catch(Exception e) {
                LOG.error("Fail to stop http server {} ", port, e);
            } finally {
                this.serverChannel = null;
            }
        }
    }
    
    public ServiceInfo getService(String name) {
        return realServer.getService(name);
    }

    public IServiceServer getServer() {
        return realServer;
    }
}