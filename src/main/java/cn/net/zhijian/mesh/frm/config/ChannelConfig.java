package cn.net.zhijian.mesh.frm.config;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.server.TcpChannel.ITcpResourceChecker;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.IPUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;
import cn.net.zhijian.util.upnp.UPnP;

/**
 * 每个服务器上需要有一个实例配置，指定开放的端口、是否运行网关、是否可外网访问等
 * 如果需要对外暴露公网访问地址，需要指定exposed
 */
public class ChannelConfig {
    private static final Logger LOG = LogUtil.getInstance();

    //内网IP，有多个内网IP时，可用配置指定哪一个
    private static final String SEG_INSIDEIP = "insideIp";
    private static final String SEG_ISGATEWAY = "isGateway";
    private static final String SEG_WANACCESSIBLE = "wanAccessible";
    private static final String SEG_TCPPORT = "tcpPort";
    private static final String SEG_HTTPPORT = "httpPort";
    private static final String SEG_TCPCHECKER = "tcpChecker"; //连接认证实现类
    private static final ChannelConfig DEFAULT = new ChannelConfig(AbsPlatform.myIp(), IConst.PORT, -1, false, true, null);
    private static ChannelConfig instance;
    
    public final String localIp; //内网地址，不包括端口，形如192.168.1.1
    public final int httpPort; //服务端http端口号
    public final int tcpPort; //服务端tcp端口号
    public final boolean isGateway; //是否按网关方式运行
    public final boolean wanAccessible; //外网是否可以访问
    public final ITcpResourceChecker tcpChecker;
    
    private ChannelConfig(String internalIp,
                int httpPort, int tcpPort, boolean isGateway,
                boolean wanAccessible, ITcpResourceChecker tcpChecker) {
        this.tcpChecker = tcpChecker;
        this.tcpPort = tcpPort;
        this.httpPort = httpPort;
        this.localIp = internalIp;
        this.isGateway = isGateway;
        this.wanAccessible = wanAccessible;
    }

    /**
     * @param cfgFile 配置文件，当更新dns、om密钥对时，会存文件
     * @return TCP通道配置
     * @throws MeshException 算法异常
     */
    
    public static ChannelConfig parse(File cfgFile) throws MeshException {
        ChannelConfig cc = DEFAULT;
        if(cfgFile.exists()) {
            Map<String, Object> cfg = JsonUtil.jsonFileToMap(cfgFile, true);
            if(cfg != null && !cfg.isEmpty()) {
                cc = parse(cfg);
            }
        }
        instance = cc;

        return cc;
    }
    
    @SuppressWarnings("unchecked")
    private static ChannelConfig parse(Map<String, Object> cfg) throws MeshException {
        int tcpPort = ValParser.getAsInt(cfg, SEG_TCPPORT, -1);//默认不启动
        if(tcpPort > 0 && (tcpPort < 1000 || tcpPort > 65535)) {
            throw new MeshException("Invalid tcp port " + tcpPort);
        }
        
        int httpPort = ValParser.getAsInt(cfg, SEG_HTTPPORT, IConst.PORT);
        if(httpPort < 1000 || httpPort > 65535) {
            throw new MeshException("Invalid http port " + httpPort);
        }

        String tcpChecker = ValParser.getAsStr(cfg, SEG_TCPCHECKER);
        ITcpResourceChecker checker = ITcpResourceChecker.DEFAULT;
        if(!StringUtil.isEmpty(tcpChecker) && tcpPort > 0) { //自定义了checker
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<? extends ITcpResourceChecker> cls;
            try {
                cls = (Class<? extends ITcpResourceChecker>)loader.loadClass(tcpChecker);
                checker = cls.getConstructor().newInstance();
            } catch (Exception e) {
                LOG.error("Fail to load ITcpResourceChecker {}, use default to instead", tcpChecker, e);
            }
        }

        String internalIp = ValParser.getAsStr(cfg, SEG_INSIDEIP);
        if(!StringUtil.isEmpty(internalIp)) {
            internalIp = IPUtil.takeOffPort(internalIp);
            if(!IPUtil.isLanIp(internalIp)) {
                internalIp = null;
            }
        }
        if(StringUtil.isEmpty(internalIp)) {
            internalIp = AbsPlatform.myIp();
        }

        boolean isGateway = ValParser.getAsBool(cfg, SEG_ISGATEWAY, false);
        boolean wanAccessible = ValParser.getAsBool(cfg, SEG_WANACCESSIBLE, true); //默认容许外网访问
        return new ChannelConfig(internalIp, httpPort, tcpPort, isGateway, wanAccessible, checker);
    }
    
    public static File configFile(String confPath) {
        return new File(FileUtil.addPath(confPath, "channel.cfg"));
    }
    
    public String localHttpAddr() {
        return IPUtil.hostFormat(localIp, httpPort);
    }

    public String localTcpAddr() {
        return IPUtil.hostFormat(localIp, tcpPort);
    }
    
    public CompletableFuture<Boolean> openPort() {
        return openPort(httpPort); //tcp端口只能内部使用，所以不存在开放upnp的问题
    }

    public CompletableFuture<Boolean> closePort() {
        return closePort(httpPort);
    }

    public CompletableFuture<Boolean> openPort(int port) {
        LOG.debug("Start to open upnp tcp port:{}", port);
        return UPnP.openTCPPort(port).whenCompleteAsync((r, e) -> {
            if (e != null) {
                LOG.error("Fail to open port {}", port, e);
            } else {
                LOG.info("{} to open port {} by UPnP", (r ? "Success" : "Fail"), port);
            }
        }, IThreadPool.Pool);
    }

    public CompletableFuture<Boolean> closePort(int port) {
        LOG.debug("Start to close upnp tcp port:{}", port);
        return UPnP.closeTCPPort(port).whenCompleteAsync((r, e) -> {
            if (e != null) {
                LOG.error("Fail to close port {}", port, e);
            } else {
                LOG.info("{} to close port {} by UPnP", (r ? "Success" : "Fail"), port);
            }
        }, IThreadPool.Pool);
    }
    
    @Override
    public String toString() {
        return "{\n\"" + SEG_INSIDEIP + "\":\"" + localIp
                + "\",\n\"" + SEG_HTTPPORT + "\":" + httpPort
                + "\",\n\"" + SEG_TCPPORT + "\":" + tcpPort
                + ",\n\"" + SEG_ISGATEWAY + "\":" + isGateway
                + ",\n\"" + SEG_WANACCESSIBLE + "\":" + wanAccessible + "\n}";
    }
    
    /**
     * 
     * @return channel配置
     */
    public static ChannelConfig instance() {
        return instance;
    }
}
