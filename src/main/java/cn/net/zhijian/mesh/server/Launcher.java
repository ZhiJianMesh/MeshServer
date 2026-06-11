package cn.net.zhijian.mesh.server;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.ServerSecurity;
import cn.net.zhijian.mesh.bean.ServerState;
import cn.net.zhijian.mesh.dbworker.TreeDBWorker;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsSearchDBWorker;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig.DeployMode;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.frm.method.SmallFileMethod;
import cn.net.zhijian.mesh.frm.process.HttpXTcpProcessor;
import cn.net.zhijian.util.LogUtil;

/**
 * 加载服务运行需要的信息
 * 1）将assets目录下的文件拷贝到files下的MESH_ROOT中；
 * 2）读取content_types.cfg文件（file下是静态文件，需要根据文件扩展名获得content-type头信息）。
 * assets目录结构(拷贝到files/MESH_ROOT后的目录结构请参照VirtualServer.java的注释)
 * /content_types.cfg
 * /logback.xml
 * /services
 *    |__service1
 *        |__bin(class, so...)
 *        |__api(api configs)
 *        |__file(static files, for example, templates)
 *        |__log(created when running)
 *        |__database.cfg(建库、建表，或者更新库、表结构，初始化数据)
 *        |__service.cfg
 *    |__service2
 * @author flyinmind of csdn.net
 */
public final class Launcher {
    private static final Logger LOG = LogUtil.getInstance();
    private static Launcher instance;
    private final ServerSecurity srvSecurity;

    private final IServiceServer realServer;
    private final HttpChannel httpChannel;
    private final TcpChannel tcpChannel;

    /**
     * 加载器
     * @param partCfg 云服务的分区配置
     */
    private Launcher(IServiceServer realServer, ServerSecurity srvSecurity,
            PartitionConfig partCfg, ChannelConfig channelCfg) throws Exception {
        SmallFileMethod.setDefaultCacheTime(partCfg.fileCacheTime);

        this.realServer = realServer;
        this.srvSecurity = srvSecurity;
        LOG.info("Start HttpChannel on {}, VM ver:{}, cpuNum:{}",
            channelCfg.localHttpAddr(), AbsPlatform.vmVersion(),
            Runtime.getRuntime().availableProcessors());
        this.httpChannel = new HttpChannel(realServer, channelCfg.httpPort);

        if(channelCfg.tcpPort > 1024) {
            LOG.info("Start TcpChannel on {}", channelCfg.tcpPort);
            this.tcpChannel = new TcpChannel(realServer, channelCfg.tcpPort, channelCfg.tcpChecker);
            HttpXTcpProcessor.setTcpChannel(tcpChannel);
        } else {
            this.tcpChannel = null;
        }
    }

    /**
     * 启动服务器
     * @param pwd 公司密码或om密码
     * @return 注册成功则返回true
     */
    public CompletableFuture<HandleResult> startServer(String pwd) {
        return srvSecurity.createSslContext().thenApplyAsync(sslContext -> {
            if(sslContext == null) {
                return new HandleResult(RetCode.INVALID_SESSION, "fail to create ssl context");
            }

            PartitionConfig partCfg = PartitionConfig.instance();
            try {
                if(!this.httpChannel.start(sslContext)) {
                    LOG.error("Fail to start http server(@{})", this.httpChannel.port);
                    return new HandleResult(RetCode.INTERNAL_ERROR, "fail to start http server");
                }
                CompanyInfo ci = CompanyInfo.instance();

                LOG.info("Success to start http server of company{} @{}", ci.id, this.httpChannel.port);
                if(this.tcpChannel != null) {
                    if(!this.tcpChannel.start(sslContext)) {
                        LOG.error("Fail to start tcp server(@{})", this.tcpChannel.port);
                        return new HandleResult(RetCode.INTERNAL_ERROR, "fail to start tcp server");
                    }
                    LOG.info("Success to start tcp server of company{} @{}",
                            ci.id, this.tcpChannel.port);
                }

                try {
                    if(!this.realServer.startServices(pwd)) {
                        LOG.error("Fail to call realServer.startServices({})", partCfg.mode);
                        return new HandleResult(RetCode.INTERNAL_ERROR, "fail to start virtual server");
                    }
                    LOG.info("Success to call virtualServer.loadServices({})", partCfg.mode);
                } catch (Exception e) {
                    LOG.error("Fail to call virtualServer.loadServices({})@{}", partCfg.mode, this.httpChannel.port, e);
                    return new HandleResult(RetCode.INTERNAL_ERROR, "fail to start virtual server");
                }

                if(CompanyInfo.instance().isRoot()) { //根环境也可能是单例运行，但是根环境无需注册
                    return HandleResult.OK;
                }

                if(partCfg.mode == DeployMode.SINGLETON) {
                    CompanyInfo.instance().registerToHttpdns();//私网单例自动注册，集群方式手动设置
                }
                return HandleResult.OK;
            } catch (Exception e) {
                LOG.error("Fail to start http server @{}", this.httpChannel.port, e);
                return new HandleResult(RetCode.INTERNAL_ERROR, e.getMessage());
            }
        }, IThreadPool.Pool).whenCompleteAsync((hr, e) -> {
            if(e != null || hr.code != RetCode.OK) {
                if(this.tcpChannel != null) {
                    this.tcpChannel.state.state(ServerState.CLOSED);
                }
                this.httpChannel.state.state(ServerState.CLOSED);
            } else {
                if(this.tcpChannel != null) {
                    this.tcpChannel.state.startupTime(System.currentTimeMillis());
                    this.tcpChannel.state.state(ServerState.RUNNING);
                }
                this.httpChannel.state.startupTime(System.currentTimeMillis());
                this.httpChannel.state.state(ServerState.RUNNING);
            }
        }, IThreadPool.Pool);
    }

    public void stopServer() {
        this.httpChannel.stop();
        if(this.tcpChannel != null) {
            this.tcpChannel.stop();
        }
        LOG.info("Close rdb/searchdb/treedb workers");
        try {
            AbsRDBWorker.destroy();
            //必须全部关掉，否则AbsSearchDBWorker会引用已经无效的AbsRDBWorker
            AbsSearchDBWorker.destroy();
            TreeDBWorker.destroy();
            LOG.info("Success to close db workers");
        } catch(Exception e) {
            LOG.error("Fail to close db workers", e);
        }
        //LOG.info("Close http client's connections");
        //HttpClient.close();
    }
    
    public ServiceInfo getService(String name) {
        return httpChannel.getService(name);
    }

    //服务运行状态
    public ServerState state() {
        return httpChannel.state;
    }

    public IServiceServer getServiceServer() {
        return this.realServer;
    }

    public boolean reloadServices() {
        return this.realServer.startServices(null);
    }

    public HttpChannel getHttpServer() {
        return this.httpChannel;
    }

    public static synchronized Launcher init(IServiceServer realServer, ServerSecurity srvSecurity,
            PartitionConfig partCfg, ChannelConfig channelCfg) {
        if(instance == null) {
            try {
                instance = new Launcher(realServer, srvSecurity, partCfg, channelCfg);
            } catch(Exception e) {
                LOG.error("Fail to create launcher", e);
                return null;
            }
        }
        return instance;
    }
}
