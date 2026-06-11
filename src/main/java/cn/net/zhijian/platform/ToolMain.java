package cn.net.zhijian.platform;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

//import cn.net.zhijian.mesh.bean.RootKeystore;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.RootKeystore;
import cn.net.zhijian.mesh.bean.ServerSecurity;
import cn.net.zhijian.mesh.client.HttpClient;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.platform.cmd.AbsCommand;
import cn.net.zhijian.platform.cmd.ApiTest;
import cn.net.zhijian.platform.cmd.CZConverter;
import cn.net.zhijian.platform.cmd.Cert;
import cn.net.zhijian.platform.cmd.Codebook;
import cn.net.zhijian.platform.cmd.Company;
import cn.net.zhijian.platform.cmd.DB;
import cn.net.zhijian.platform.cmd.Download;
import cn.net.zhijian.platform.cmd.EccKey;
import cn.net.zhijian.platform.cmd.FileOperation;
import cn.net.zhijian.platform.cmd.Hash;
import cn.net.zhijian.platform.cmd.IPToAddr;
import cn.net.zhijian.platform.cmd.IdleTags;
import cn.net.zhijian.platform.cmd.Json;
import cn.net.zhijian.platform.cmd.KeyID;
import cn.net.zhijian.platform.cmd.Md5;
import cn.net.zhijian.platform.cmd.MergeWords;
import cn.net.zhijian.platform.cmd.Pbkdf2;
import cn.net.zhijian.platform.cmd.PortMapping;
import cn.net.zhijian.platform.cmd.Queue;
import cn.net.zhijian.platform.cmd.Release;
import cn.net.zhijian.platform.cmd.RootKey;
import cn.net.zhijian.platform.cmd.Service;
import cn.net.zhijian.platform.cmd.Sha1;
import cn.net.zhijian.platform.cmd.Sha256;
import cn.net.zhijian.platform.cmd.Source;
import cn.net.zhijian.platform.cmd.TotpKey;
import cn.net.zhijian.platform.cmd.UUID;
import cn.net.zhijian.platform.cmd.Utc;
import cn.net.zhijian.platform.util.SrvKeystoreHelper;
//import cn.net.zhijian.platform.util.SrvKeystoreHelper;
import cn.net.zhijian.platform.util.SrvPlatformUtil;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * 系统启动类
 * @author flyinmind of csdn.net
 *
 */
public final class ToolMain {
    private static final Map<String, AbsCommand> commands = new HashMap<>();
    
    private static void addCmd(AbsCommand cmd) {
        commands.put(cmd.name.toLowerCase(), cmd);
    }
    
    static {
        addCmd(new Company("company"));
        addCmd(new EccKey("ecckey"));
        addCmd(new Utc("utc"));
        addCmd(new Hash("hash"));
        addCmd(new KeyID("keyid"));
        addCmd(new Json("json"));
        addCmd(new UUID("uuid"));
        addCmd(new Codebook("codebook"));
        addCmd(new RootKey("rootkey"));
        addCmd(new Release("release"));
        addCmd(new Source("source"));
        addCmd(new IdleTags("idletags"));
        addCmd(new PortMapping("upnp"));
        addCmd(new CZConverter("czconvert"));
        addCmd(new IPToAddr("iptoaddr"));
        addCmd(new MergeWords("mergewords"));
        addCmd(new Md5("md5"));
        addCmd(new Sha1("sha1"));
        addCmd(new Sha256("sha256"));
        addCmd(new TotpKey("totp"));
        addCmd(new Cert("cert")); //自签名证书管理
        addCmd(new ApiTest("apitest")); //接口测试
        addCmd(new Pbkdf2("pbkdf2")); //密码加密
        addCmd(new Service("service")); //服务管理
        addCmd(new FileOperation("file")); //文件操作，删除、计算摘要、转base64
        addCmd(new Queue("queue")); //查看queue读取状态
        addCmd(new Download("download")); //下载文件
        addCmd(new DB("db")); //db备份与恢复
    }

    public static void main(String[] args) throws Exception {
        String workDir = System.getProperty("user.dir");
        String confPath = FileUtil.addPath(workDir, IConst.SYS_CONF_DIR);
        String logCfgFile = FileUtil.addPath(confPath, "cmdlogback.xml");
        try {
            //日志初始化放到static中，否则阻止不了netty的无用日志，因为它在static中打印的
            if(!LogUtil.init(logCfgFile, workDir, false)) {
                System.out.println("Fail to load log config file:" + logCfgFile);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        AbsPlatform.init(new SrvPlatformUtil(workDir));
        RootKeystore.setHelper(new SrvKeystoreHelper(confPath));
        RootKeystore.init(); //company、rootkey命令中用到
        ServiceInfo.setWorkDir(workDir);
        ServerSecurity.init(confPath);
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            HttpClient.close();
        }));
        
        //加载实例的配置，目前只有一个内网地址，可以不配置
        File channelCfgFile = new File(FileUtil.addPath(confPath, "channel.cfg"));
        ChannelConfig.parse(channelCfgFile);
        
        File partCfgFile = new File(FileUtil.addPath(confPath, "partition.cfg"));
        PartitionConfig partCfg = PartitionConfig.parse(partCfgFile, IPlatformConst.EVM);
        if(partCfg == null) {
            System.out.println("Fail to load partition.cfg, it's null");
            System.exit(0);
            return;
        }
        PartitionConfig.instance(partCfg);
        ServiceInfo backend = new ServiceInfo(
                ServiceInfo.serviceHome(IConst.SERVICE_BACKEND),
                IConst.SERVICE_BACKEND);
        ServiceClient.setBackendService(backend);
        ServiceClient.setCloudDns(IConst.SERVICE_HTTPDNS, partCfg.cloudHttpdns, -1);

        CompanyInfo.init(CompanyInfo.configFile(confPath));
        CompanyInfo localCompany = CompanyInfo.instance();
        if(localCompany == null) {
            System.out.println("Fail to load company info from perference");
            System.exit(1);
        }

        String cmd = args.length == 0 ? null : args[0].toLowerCase();
        AbsCommand act;
        if(cmd == null || (act = commands.get(cmd)) == null) {
            AbsCommand.printHelp(commands);
            return;
        }
        act.setPartConfig(partCfg);
        act.setWorkDir(workDir);
        String[] paras = StringUtil.removeEle(args, 0);
        if(act.run(paras)) {
            System.out.println("Succeeded");
        } else {
            System.out.println("Failed");
        }
        System.exit(0);
    }
}
