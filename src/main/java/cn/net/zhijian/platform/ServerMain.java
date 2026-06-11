package cn.net.zhijian.platform;

import java.io.File;

import org.slf4j.Logger;
import org.sqlite.SQLiteJDBCLoader;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.RootKeystore;
import cn.net.zhijian.mesh.bean.ServerSecurity;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsAssets;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.frm.service.backend.BackendBase;
import cn.net.zhijian.mesh.server.GatewayServer;
import cn.net.zhijian.mesh.server.Launcher;
import cn.net.zhijian.mesh.server.ServiceServer;
import cn.net.zhijian.platform.util.AssetsWrapper;
import cn.net.zhijian.platform.util.SrvKeystoreHelper;
import cn.net.zhijian.platform.util.SrvPlatformUtil;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.FileUtilBase;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * 在jvm中的启动类
 * @author flyinmind of csdn.net
 *
 */
public final class ServerMain implements IThreadPool {
    public static void main(String[] args) {
        String workDir = System.getProperty("user.dir");
        String confPath = FileUtilBase.addPath(workDir, IConst.SYS_CONF_DIR);
        //初始化日志相关的配置，如果日志初始化失败则退出
        //日志初始化不能放在静态区执行，尚不知原因
        String logCfgFile = FileUtilBase.addPath(confPath, "logback.xml");
        String logsPath = FileUtilBase.addPath(workDir, "logs");
        try {
            if(!LogUtil.init(logCfgFile, logsPath, true)) {
                System.out.println("Fail to load log config file:" + logCfgFile);
            }
        } catch(Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        AbsPlatform.init(new SrvPlatformUtil(workDir));
        //消除netty的UnsupportedOperationException
        //放在命令行-D参数中，会导致提前使用logback，此前并未初始化
        //所以它会加载使用默认的ConsoleAppender，导致console中出现两个相同的日志
        System.setProperty("io.netty.tryReflectionSetAccessible", "true");
       
        Logger LOG = LogUtil.getInstance();
        try {
            run(args, workDir, confPath, LOG);
        } catch(Exception e) {
            LOG.error("Fail to run main", e);
            System.exit(10);
        }
    }
    
    private static void run(String[] args, String workDir, String confPath, Logger LOG) throws Exception {
        LOG.debug("Args:{},runtime:{},os.name:{},os.arch:{}",
                StringUtil.joinArray(args, ","),
                System.getProperty("java.runtime.name", ""),
                System.getProperty("os.name"),
                System.getProperty("os.arch"));

        //偶尔出现sqlite加载“native library”失败，所以提前加载一下
        //sqlite是treedb、searchdb、bios必须使用的，所以提前加载，并不浪费
        //安卓平台不需要预加载，因为使用的是系统的sqlite实现，早已加载过
        LOG.info("Start to preload sqlite driver");
        SQLiteJDBCLoader.initialize();

        AbsAssets.init(new AssetsWrapper(ServerMain.class.getClassLoader()));
        //加载实例的配置，只有一个内网地址时，可以不配置
        //必须先加载ChannelConfig，再加载PartitionConfig，最后加载CompanyInfo
        File channelCfgFile = ChannelConfig.configFile(confPath);
        ChannelConfig channelCfg = ChannelConfig.parse(channelCfgFile); //不可能为空
        String localAddr = channelCfg.localHttpAddr();
        LOG.debug("ChannelConfig:{}, local addr:{}", channelCfg, localAddr);
        
        ServiceInfo.setWorkDir(workDir);
        BackendBase.createService(workDir);
        
        //首次启动时需要用到，可以是om密码，也可以是公司密码
        //主BIOS服务首次启动时必须时om密码，keystore服务首次启动时必须使用公司密码
        //其他的建议都使用om密码
        //运维公钥是非常重要的配置，反应了系统是在那个运维平台下安装起来的,
        //以后也只能由相同的运维平台来操作,其他运维平台的操作都会被拒绝
        //startup [pwd[,biosSrv]]
        String pwd = args.length > 0 ? args[0] : "";
        
        /**
         * 运维公钥是非常重要的配置，反应了系统是在那个运维平台下安装起来的,
         * 以后也只能由相同的运维平台来操作,其他运维平台的操作都会被拒绝
         */
        File partCfgFile = PartitionConfig.configFile(confPath);
        PartitionConfig partCfg;
        if(!partCfgFile.exists()) {
            String biosSrv = args.length > 1 ? args[1] : "";
            if(StringUtil.isEmpty(biosSrv)) {
                LOG.error("{} not exists,and no bios parameter", partCfgFile);
                throw new MeshException("no bio server parameter when no partition.cfg");
            }
            
            if(biosSrv.equals(localAddr)) { //bios实例上必须有partition.cfg，且不能向自己请求
                LOG.error("{} not exists in a bios node", partCfgFile);
                throw new MeshException("no partition.cfg");
            }
            
            partCfg = PartitionConfig.downloadCfg(partCfgFile, biosSrv);
        } else {
            partCfg = PartitionConfig.parse(partCfgFile, IPlatformConst.EVM);
            //bios实例查询自身，可能查不到，因为还没启动，所以在bios.Watcher中设置主实例
            if(!partCfg.biosDNS().isBios(localAddr)) {
                partCfg.biosDNS().initMain();
            }
        }
        PartitionConfig.instance(partCfg);
        
        LOG.info("Server timezone offset:{}", partCfg.timeZone);
        ServiceClient.setCloudDns(IConst.SERVICE_HTTPDNS, partCfg.cloudHttpdns, -1);
        //是bios服务器的情况下才需要加载根密钥及公司信息，所以在此不加载，只设置工具类
        SrvKeystoreHelper ksHelper = new SrvKeystoreHelper(confPath);
        LOG.info("Key store file:{}", ksHelper.configFile());
        RootKeystore.setHelper(ksHelper);

        LOG.info("Mesh server starting,run mode:{},bios:{}", partCfg.mode.name(), partCfg.biosDNS().toString());
        
        File companyFile = CompanyInfo.configFile(confPath);
        //有bios服务的实例，必须登录后才能启动
        if(!companyFile.exists()) {
            if(partCfg.biosDNS().isBios(localAddr)) {
                LOG.error("{} not exists in bios node,please login first", companyFile);
                throw new MeshException("no company info,please login first");
            }
            if(StringUtil.isEmpty(pwd)) {
                LOG.error("{} not exists,and no omPwd parameter", companyFile);
                throw new MeshException("no company.cfg");
            }
            //从mainBios中下载
            String s = BiosClient.downloadCfgFile("company", pwd);
            byte[] buf = ByteUtil.base642bin(s);
            FileUtil.writeFile(companyFile, buf);
        }
        CompanyInfo.init(companyFile);
        
        ServerSecurity.init(confPath);
        File caFile = ServerSecurity.configFile();
        if(!caFile.exists()) {
            if(partCfg.isMainBios()) { 
                LOG.error("{} not exists in main bios node,please login first", caFile);
                throw new MeshException("no ca,please login first");
            }
            if(StringUtil.isEmpty(pwd)) {
                LOG.error("{} not exists,and no omPwd parameter", caFile);
                throw new MeshException("no server.bks");
            }
            String s = BiosClient.downloadCfgFile("ca", pwd);
            byte[] buf = ByteUtil.base642bin(s);
            ServerSecurity.saveServerCA(buf);
        }
        ServerSecurity srvSecurity = ServerSecurity.load();
        if(srvSecurity == null) {
            LOG.error("Invalid ca file `{}`", caFile);
            throw new MeshException("invalid ca file");
        }

        if(LOG.isDebugEnabled()) {
            CompanyInfo ci = CompanyInfo.instance();
            LOG.debug("company id:{}", ci.id);
            LOG.debug("company name:{}", ci.name());
            LOG.debug("access code:{}", ci.accessCode());
            LOG.debug("credit code:{}", ci.creditCode);
            LOG.debug("inside addr:{}", ci.insideAddr);
            LOG.debug("outside addr:{}", ci.outsideAddr());
            LOG.debug("public key:{}", ci.authKeyPair().publicKey2Str());
        }

        IServiceServer realServer;
        if(channelCfg.isGateway) {
            //作为服务网关使用，从ServiceServer继承，当本地不能处理时，转发接口调用
            //与ServiceServer不能共存
            realServer = new GatewayServer(workDir, pwd);
        } else {
            realServer = new ServiceServer(workDir);
        }
        Launcher launcher = Launcher.init(realServer, srvSecurity, partCfg, channelCfg);
        if(launcher == null) {
            LOG.error("Fail to call Launcher.init");
            System.exit(7);
            return; //消除eclipse的null检查告警
        }

        LOG.info("Register shutdown hook");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("shutdown, stop server");
            launcher.stopServer();
        }));
        
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            LOG.error("Uncaught exception", e);
            launcher.stopServer();
        });

        try {
            launcher.startServer(pwd).whenCompleteAsync((hr, e) -> {
                if(e != null || hr == null || hr.code != RetCode.OK) { 
                    LOG.error("Fail to start mesh server @{}, result:{}",
                            channelCfg.localHttpAddr(), hr != null ? hr.brief() : "{}");
                    launcher.stopServer();
                    System.exit(8);
                    return; //消除eclipse的null检查告警
                }
                LOG.info("Mesh server started @{}, result:{}", channelCfg.localHttpAddr(), hr.brief());
            }, Pool);
        } catch(Exception e) {
            LOG.error("Fail to start mesh server {}", channelCfg.localHttpAddr(), e);
            launcher.stopServer();
            System.exit(9);
        }
    }
}
