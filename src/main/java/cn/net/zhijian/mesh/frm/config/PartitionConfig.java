package cn.net.zhijian.mesh.frm.config;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.BiosDNS;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.bean.ServiceDNS;
import cn.net.zhijian.mesh.client.HttpClient;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.platform.IPlatformConst;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.IUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 每个服务都需要的公共配置信息，
 * 这些配置信息放在工程的conf目录下，在安装时才能确定，每个partition可以不同，
 * 通常同一partition中，是一致的。
 * 只有biosDns可以通过OM接口在运行时更改，其他参数都不能通过OM平台也下发。
 * 
 * @author flyinmind of csdn.net
 *
 */
public class PartitionConfig {
    private static final int MAX_PUB_PARTITION = 10000; //公共分区保留id，0-10000
    //分区信息在accesstoken中占3个base64字符(18位)，所以最大2^18=262144，250000之后的作为私有云分区
    //私有云默认的分区号，不同公司私有云是分开的，所以用相同的分区号并不会出现冲突
    public static final int PRIVATE_CLOUD_PARTITION = 250000;

    /**
     * 运维平台给应用实例发送命令时，使用私钥签名，应用需要使用运维平台的公钥验签，
     * 验签通过后，才可以执行相应的的动作。
     * 运维公钥是非常重要的配置，反应了系统是在那个运维平台下安装起来的,
     * 以后也只能由相同的运维平台来操作,其他运维平台的操作都会被拒绝。
     */
    private static final String SEG_BIOS_SERVERS = "biosServers";
    private static final String SEG_PARTITION = "partition";
    //外网IP，在公有云服务器上，或通过网关映射端口时，检测不到外网IP，所以需要设置
    private static final String SEG_INCLOUD = "inCloud";
    private static final String SEG_FILECACHETIME = "fileCacheTime";
    private static final String SEG_CLOUD_HTTPDNS = "cloudHttpdns";
    private static final String SEG_TIMEZONE = "timeZone";
    private static final String SEG_RUNMODE = "runMode"; //运行模式
    
    //公司服务运行方式
    public enum DeployMode {
        SINGLETON, //公司自己单例部署，只有一个实例，此时会自动初始化数据库
        CLUSTER; /* 公司自己集群部署，有多个实例共同工作，此时不会自动化初始数据库，
                    服务安装时由执行的实例初始化数据库，多实例安装会多次初始化，所以数据库初始化脚本要能重入。
                    即使部署在公有云上，只要不是至简网格的集群，都是私网集群，
                    需要设置outside、inside */

        public static DeployMode getValue(String name) {
            String s = StringUtil.isEmpty(name) ? IConst.EMPTY_STR : name.toUpperCase();
            if(CLUSTER.name().equals(s)) {
                return CLUSTER;
            }
            return SINGLETON;
        }
    }

    protected final File cfgFile; //配置文件名，因为在om上可以更改此文件的内容
    public final int partition; //需要的分区编号，最多25万，将服务实例分成多个区，便于隔离

    public final String[] cloudHttpdns; //云侧httpdns服务器地址列表，主要用于特殊部署环境
    public final int fileCacheTime; //静态文件缓存时间，单位毫秒

    /*
     *  是否为单例模式运行，此种模式中，需要启动时自动在bios中注册，及初始化数据库，
     *  必须在partition.cfg中拥有om的私钥才可以，运行在android中就是单例模式，可以这样调用
     */
    public final DeployMode mode;
    public final boolean isInCloud; //公司服务器是否运行在公有云环境
    public final String environment; //工作的环境
    public final int timeZone; //时区偏移，单位为分钟，比如东八区为480

    /**
     * bios服务节点的ip:port，可以有多个，逗号分隔，
     * 安装后，可由运维平台设置
     * //减少bios服务节点发现的请求
     */
    private BiosDNS biosDns;
    private static PartitionConfig instance;

    /**
     * @param cfgFile 配置文件，当更新dns、om密钥对时，会存文件
     * @param cfg 配置内容
     * @param enviorment 环境，用于请求时填充agent
     * @return 分区配置
     * @throws MeshException 算法异常
     */
    static PartitionConfig parse(File cfgFile, Map<String, Object> cfg, String enviorment) throws MeshException {
        //java运行环境默认在公有云服务器，安卓环境一定在私有云
        boolean inCloud;
        if(AbsPlatform.environment().startsWith("android_")) {
            inCloud = false;
        } else {
            inCloud = ValParser.getAsBool(cfg, SEG_INCLOUD, false);
        }
        
        int partition = ValParser.getAsInt(cfg, SEG_PARTITION, PRIVATE_CLOUD_PARTITION);
        int fileCacheTime = ValParser.getAsInt(cfg, SEG_FILECACHETIME, 300*1000);
        int timeZone = TimeZone.getDefault().getRawOffset() / 60000;
        if(cfg.containsKey(SEG_TIMEZONE)) {
            timeZone = ValParser.getAsInt(cfg, SEG_TIMEZONE, timeZone);
        }
        
        DeployMode runMode = DeployMode.getValue(ValParser.getAsStr(cfg, SEG_RUNMODE));

        String[] cloudHttpdns;
        List<String> servers = ValParser.getAsStrList(cfg, SEG_CLOUD_HTTPDNS);
        if(servers == null || servers.isEmpty()) {
            cloudHttpdns = new String[] {IConst.API_DOMAIN + ':' + IConst.PORT};
        } else {
            cloudHttpdns = servers.toArray(new String[] {});
        }
        List<String> biosServers = ValParser.getAsStrList(cfg, SEG_BIOS_SERVERS);
        if(biosServers == null || biosServers.isEmpty()) {
            biosServers = new ArrayList<>();
            biosServers.add(ChannelConfig.instance().localHttpAddr());//默认只有本机是bios
        }
        return new PartitionConfig(cfgFile, inCloud,
                partition, cloudHttpdns, fileCacheTime,
                runMode, enviorment, timeZone, biosServers);
    }

    public static PartitionConfig parse(File cfgFile, String enviorment) throws MeshException {
        Map<String, Object> cfg = null;
        if(cfgFile.exists()) {
            cfg = JsonUtil.jsonFileToMap(cfgFile, true);
        }
        
        if(cfg == null) {
            PartitionConfig partCfg = parse(cfgFile, new HashMap<>(), enviorment);
            partCfg.save();
            return partCfg;
        }
        return parse(cfgFile, cfg, enviorment);
    }

    public static void instance(PartitionConfig instance) {
        PartitionConfig.instance = instance;
    }
    
    public static PartitionConfig instance() {
        return instance;
    }

    public static File configFile(String confPath) {
        return new File(FileUtil.addPath(confPath, "partition.cfg"));
    }

    private PartitionConfig(File cfgFile, boolean inCloud,
            int partition, String[] cloudHttpdns,
            int fileCacheTime, DeployMode mode, String environment,
            int timeZone, List<String> biosDns) throws MeshException {
        this.cfgFile = cfgFile;
        //公有云运行时，设为true，此时不会判断用户访问是否来自外网
        this.isInCloud = inCloud;
        this.partition = partition;
        this.cloudHttpdns = cloudHttpdns;
        this.fileCacheTime = fileCacheTime;
        this.mode = mode;
        this.environment = environment;
        this.timeZone = timeZone;
        updateBiosDns(biosDns, biosDns.get(0), false);
    }

    /**
     * 判断token中的partition是否与配置中的partition一致
     * @param token 令牌
     * @return 一致则返回true
     */
    public boolean isPartitionValid(String token) {
        /*
         * partition<=0的分区，是公共分区，
         * 无论是作为请求方，还是作为背调方，都无需校验
         */
        if(this.partition < MAX_PUB_PARTITION) { //被调用方是在公共分区
            return true;
        }

        int partition = AccessToken.getPartition(token);
        if(partition < MAX_PUB_PARTITION) {//调用方来自公共分区
            return true;
        }

        return partition == this.partition;
    }
    
    public boolean isPrivate() {
        return this.partition >= PRIVATE_CLOUD_PARTITION;
    }

    public BiosDNS biosDNS() {
        return biosDns;
    }

    public boolean isMainBios() {
        NodeAddress n = biosDns.mainNode();
        return n.addr.equals(ChannelConfig.instance().localHttpAddr());
    }

    public NodeAddress mainBios() {
        return biosDns.mainNode();
    }
    
    /**
     * 在backend接到bios服务器更改通知时，调用此函数
     * @param biosServers 新的bios服务器列表
     * @param main 主bios
     * @param saveIt 是否保存成功
     * @return true成功，false失败
     * @throws MeshException mesh异常
     */
    public boolean updateBiosDns(List<String> biosServers, String main, boolean saveIt) throws MeshException {
        String[] addrs = biosServers.toArray(new String[]{});
        if(addrs.length == 0 || !StringUtil.matches(addrs, StringUtil.ADDR_PATTERN)) {
            return false;
        }
        this.biosDns = new BiosDNS(biosServers, main);

        ServiceDNS sd = ServiceDNS.create(IConst.SERVICE_BIOS, addrs, 0);
        //减少bios服务节点发现的请求
        ServiceClient.setLocalDns(IConst.SERVICE_BIOS, sd);

        return !saveIt || save();
    }
    
    public boolean save() {
        return FileUtil.writeFile(cfgFile, toString(), IUtil.DEFAULT_CHARSET);
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n\"").append(SEG_PARTITION).append("\":").append(partition)
          .append(",\n\"").append(SEG_FILECACHETIME).append("\":").append(fileCacheTime)
          .append(",\n\"").append(SEG_INCLOUD).append("\":\"").append(isInCloud)
          .append("\",\n\"").append(SEG_BIOS_SERVERS).append("\":").append(biosDns.toString());

        if(cloudHttpdns != null && cloudHttpdns.length > 0) {
            sb.append(",\n\"").append(SEG_CLOUD_HTTPDNS).append("\":[");
            for (int i = 0; i < cloudHttpdns.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(cloudHttpdns[i]).append('"');
            }
            sb.append(']');
        }
        sb.append("\n}");
        return sb.toString();
    }
    
    /**
     * 从任意一个相邻节点获取partition.cfg
     * @param cfgFile 配置文件
     * @param biosSrv 相邻节点的地址
     * @return 配置信息
     * @throws MeshException 超时异常
     */
    public static PartitionConfig downloadCfg(File cfgFile, String biosSrv) throws MeshException {
        NodeAddress node = new NodeAddress(IConst.SERVICE_BIOS, biosSrv, 0, 0);
        String url = HttpClient.serviceApiUrl(IConst.SCHEME, IConst.SERVICE_BIOS, "/partition", biosSrv);

        try {
            HandleResult hr = HttpClient.getFrom(node, url, null, "getPartition").get(3, TimeUnit.SECONDS);
            if(hr.code != RetCode.OK) {
                throw new MeshException("fail to get partition from " + biosSrv);
            }
            String cfg = ValParser.getAsStr(hr.data, "cfg");
            if(StringUtil.isEmpty(cfg)) {
                throw new MeshException("invalid partition from " + biosSrv);
            }
            FileUtil.writeFile(cfgFile, cfg, IConst.DEFAULT_CHARSET);
            PartitionConfig pc = PartitionConfig.parse(cfgFile, IPlatformConst.EVM);
            String main = ValParser.getAsStr(hr.data, "main");
            pc.biosDNS().setMain(main);
            return pc;
        } catch (Exception e) {
            throw new MeshException("fail to get partition from " + biosSrv, e);
        }
    }
}
