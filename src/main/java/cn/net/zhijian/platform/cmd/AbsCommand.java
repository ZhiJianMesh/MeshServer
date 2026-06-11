package cn.net.zhijian.platform.cmd;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.FileUtil;

public abstract class AbsCommand {
    public final String name;
    protected String workDir;
    protected String configDir;
    protected PartitionConfig partCfg;
    public abstract boolean run(String[] args) throws Exception;
    public abstract String[] help();
    
    AbsCommand(String name) {
        this.name = name;
    }
    
    public void setPartConfig(PartitionConfig partCfg) {
        this.partCfg = partCfg;
    }
    
    public void setWorkDir(String workDir) {
        this.workDir = workDir;
        this.configDir = FileUtil.addPath(workDir, IConst.SYS_CONF_DIR);
    }
    
    public static void printHelp(Map<String, AbsCommand> commands) {
        int len = 0;
        String blanks = IConst.EMPTY_STR;
        List<String> keys = new ArrayList<>();
        for(Map.Entry<String, AbsCommand> o : commands.entrySet()) {
            if(o.getKey().length() > len) { //计算最长的命令名称
                len = o.getKey().length();
            }
            keys.add(o.getKey());
        }
        while(blanks.length() < len) {
            blanks += ' '; //在说明前面保留空格数量
        }
        blanks += "  ";
        
        keys.sort((a,b)->{return a.compareTo(b);});
        for(String k : keys) {
            AbsCommand cmd = commands.get(k);
            String[] ss = cmd.help();
            System.out.println(String.format(" %-" + len + "s:%s", k, ss[0]));
            
            for(int i = 1; i < ss.length; i++) {
                System.out.print(blanks);
                System.out.println(ss[i]);
            }            
        }
    }
    
    public static void printHelp(String[] ss) {
        printHelp(ss, null);           
    }
    
    public static void printHelp(String[] ss, String[] exts) {
        for(String s : ss) {
            System.out.println(s);
        }
        if(exts != null) {
            for(String s : exts) {
                System.out.println(s);
            }
        }
    }

    /**
     * 只可用于私有云，给本机的vCloud中Command处理类发送命令
     * @param cmd 命令
     * @param params 参数
     * @return 异步结果
     */
    protected CompletableFuture<HandleResult> postCommand(String cmd, Map<String, Object> params) {
        CompanyInfo local = CompanyInfo.instance();
        if(local == null) {
            System.out.println("Haven't logined");
            return HandleResult.future(RetCode.NO_RIGHT);
        }
        
        AccessToken at = local.adminToken(IConst.SERVICE_COMPANY);
        if(at == null) {
            System.out.println("Fail to create access token for company " + local.id);
            return HandleResult.future(RetCode.NO_RIGHT);
        }

        String adminToken = at.generate();
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
            .url("/command")
            .traceId("db_cmd_" + local.id)
            .cid(local.id)
            .token(adminToken)
            .put("cmd", cmd);
        if(params != null) {
            req.putAll(params);
        }
        //只可以调用本实例的命令
        String addr = "localhost:" + ChannelConfig.instance().httpPort;
        
        return ServiceClient.servicePost(addr, req);
    }
}
