package cn.net.zhijian.platform.cmd;

import java.io.File;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig.DeployMode;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.platform.IPlatformConst;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.DateUtil.PeriodType;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.IPUtil;
import cn.net.zhijian.util.IPUtil.FORMAT;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.Totp;
import cn.net.zhijian.util.ValParser;

/**
 * 公司登录、更新accesscode、修改公司信息等操作。
 * 不能支持register操作，因为没有验证码，这个命令就成了工具工具
 */
public class Company extends AbsCommand {
    private static final int CMD_TOTP_TIMESTEP = 60;
    
    public Company(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if(args.length < 1) {
            printHelp(help());
            return false;
        }
        
        String cmd = args[0].toLowerCase();
        String[] args1 = StringUtil.removeEle(args, 0);
        
        //login/register时，本地配置中可能还没有设置公司信息，
        //所以login之前不能调用loadLocalFromPreference
        if(cmd.equals("register")) {
            return register(args1);
        }  
        
        if(cmd.equals("login")) {
            return login(args1);
        }

        if(cmd.equals("pubkey")) {
            return publicKey(args1);
        }
        
        if(cmd.equals("chgauth")) {
            return changeAuth(args1);
        }
        
        if(cmd.equals("setinfo")) {
            return setInfo(args1);
        }
        
        if(cmd.equals("chgpwd")) {
            return changePassword(args1);
        }

        if(cmd.equals("accesscode")) {
            return accessCode(args1);
        }
        
        if(cmd.equals("info")) {
            return info(args1);
        }
        
        if(cmd.equals("backupat")) {
            return backupAt(args1);
        }
        
        if(cmd.equals("settotp")) {
            return setTotp(args1);
        }
        
        if(cmd.equals("gettotp")) {
            return getTotp(args1);
        }
        
        if(cmd.equals("enginever")) {
            System.out.println(IConst.ENGINEVERSION);
            return true;
        }
        
        printHelp(help());
        return true;
    }

    @Override
    public String[] help() {
        return new String[]{name + ",a set of company commands",
                "1)register company_name company_creditcode password cfm_password session verify_code",
                "  get session and verify_code from `http://http://www.zhijian.net.cn/www/cmdaide.html`",
                "2)login company_id password [outside_addr [inside_addr]]",
                "3)pubKey - display auth-public-key",
                "4)chgpwd old_password new_password",
                "5)chgAuth password",
                "6)setinfo company_name [country [province [city [info]]]]",
                "7)accesscode [code|generate]",
                "8)info",
                "9)backupAt [backupAt]",
                "10)settotp",
                "11)gettotp [your_secret[ code_len[ time_step]] | generate[ code_len]]",
                "12)engineVer"
                };
    }

    private boolean login(String[] args) throws Exception {
        if(args.length < 2) { //company_id password [outside_addr [inside_addr]]
            printHelp(help());
            return false;
        }

        //先本地加载信息，前面生成的accesscode、公司密钥对尽量保持不变
        String pwd = args[1];
        String outsideAddr = null;
        String insideAddr = null;
        for(int i = 2; i < args.length; i++) {
            String addr = IPUtil.takeOffPort(args[i]);
            if(!IPUtil.isValidIP(addr)) {
                System.out.println("Invalid ip " + addr);
                return false;
            }
            if(IPUtil.isLanIp(addr)) {
                insideAddr = addr;
            } else {
                outsideAddr = addr + ":" + ChannelConfig.instance().httpPort;
            }
        }
        insideAddr = getInsideAddr(insideAddr) + ":" + ChannelConfig.instance().httpPort;
      
        int cid = Integer.parseInt(args[0]);
        HandleResult result = CompanyInfo.login(cid, pwd, insideAddr, outsideAddr).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                System.out.println("Fail to login");
                return CompletableFuture.completedFuture(hr);
            }

            try {
                CompanyInfo.init(CompanyInfo.configFile(configDir));
                CompanyInfo ci = CompanyInfo.instance();
                if(!ci.isValid()) {
                    return HandleResult.future(RetCode.INTERNAL_ERROR, "Fail to parse company.cfg");
                }
                return CompletableFuture.completedFuture(hr);
            } catch (MeshException e) {
                e.printStackTrace();
                return HandleResult.future(RetCode.INTERNAL_ERROR, "Fail to parse company.cfg");
            }
        }).exceptionally(e -> {
            System.out.println("Fail to login");
            e.printStackTrace();
            return HandleResult.InternalError;
        }).get(10, TimeUnit.SECONDS);
        
        if(result.code == RetCode.OK) {
            //必须重新加载一遍，因为配置可能原本不存在，或存在，但是authKey已发生变更
            CompanyInfo.init(CompanyInfo.configFile(configDir));
            CompanyInfo ci = CompanyInfo.instance();
            ci.setOutsideAddr(outsideAddr);
            System.out.println("company id:" + ci.id);
            System.out.println("company name:" + ci.name());
            System.out.println("access code:" + ci.accessCode());
            System.out.println("credit code:" + ci.creditCode);
            System.out.println("external addr:" + ci.outsideAddr());
            System.out.println("internal addr:" + ci.insideAddr);
        }

        System.out.println("Register httpdns result:" + result.brief());

        return result.code == RetCode.OK;
    }
    
    private boolean register(String[] args) throws Exception {
        //0)company_name
        //1)company_creditcode
        //2)password
        //3)cfm_password
        //4)session
        //5)verify_code
        //6]insideAddr
        //5]outsideAddr
        if(args.length < 6) {
            printHelp(help());
            return false;
        }

        int i = 0;
        String name = args[i++];
        String creditCode = args[i++];
        String pwd = args[i++];
        String cfmPwd = args[i++];
        String session = args[i++];
        String verifyCode = args[i++];
        String insideAddr = null;
        String outsideAddr = null;
        DeployMode mode = PartitionConfig.instance().mode;
        
        Locale loc = Locale.getDefault();
        String country = loc.getCountry();
        String province = IConst.EMPTY_STR;
        String city = IConst.EMPTY_STR;
        String county = IConst.EMPTY_STR;
        String info = IConst.EMPTY_STR;

        for(; i < args.length; i++) {
            String addr = args[i];
            if(IPUtil.isValidIP(addr, FORMAT.IP.v + FORMAT.PORT.v) != FORMAT.NONE) {
                System.out.println("Invalid ip " + addr);
                return false;
            }
            if(IPUtil.isLanIp(addr)) {
                insideAddr = addr;
            } else {
                outsideAddr = addr;
            }
        }
        insideAddr = getInsideAddr(insideAddr);
        
        HandleResult result = CompanyInfo.register(creditCode, pwd, cfmPwd, name,
                country, province, city, county,
                info, session, verifyCode, insideAddr, outsideAddr).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                System.out.println("Fail to register");
                e.printStackTrace();
                return;
            }
            
            if(hr.code != RetCode.OK) {
                System.out.println("Register(" + name + "," + creditCode  + ") result:" + hr.brief());
                return;
            }

            //必须重新加载一遍，因为配置可能原本不存在，或存在，但是authKey已发生变更
            CompanyInfo ci = CompanyInfo.instance();
            System.out.println("company id:" + ci.id);
            System.out.println("company name:" + ci.name());
            System.out.println("access code:" + ci.accessCode());
            System.out.println("credit code:" + ci.creditCode);
            System.out.println("external addr:" + ci.outsideAddr());
            System.out.println("internal addr:" + ci.insideAddr);
            System.out.println("runMode:" + mode.name());
            initCfgFiles();
        }).get(15, TimeUnit.SECONDS);

        return result.code == RetCode.OK;
    }
   
    private void initCfgFiles() {
        try {
            File partFile = PartitionConfig.configFile(configDir);
            if(!partFile.exists()) {
                PartitionConfig pc = PartitionConfig.parse(partFile, IPlatformConst.EVM);
                pc.save();//产生一个默认的配置
            }
            File channelFile = ChannelConfig.configFile(configDir);
            if(!channelFile.exists()) {
                ChannelConfig cc = ChannelConfig.parse(channelFile);
                FileUtil.writeFile(channelFile, cc.toString(), IConst.DEFAULT_CHARSET);
            }
            File keyFile = new File(FileUtil.addPath(configDir, "totp.key"));
            if(!keyFile.exists()) {
                String key = Totp.generateSecret(Totp.DEFAULT_CODE_DIGITS);
                FileUtil.writeFile(keyFile, key, IConst.DEFAULT_CHARSET);
            }
        } catch (MeshException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
    
    private InetAddress getSuitableAddr(List<InetAddress> addrs,
            Class<? extends InetAddress> cls, boolean local) {
        for(InetAddress addr : addrs) {//排在前面的优先
            if(cls.isInstance(addr)) {
                if(addr.isLoopbackAddress()) { 
                    continue;
                }
                if(local) {
                    if(addr.isSiteLocalAddress()) {
                        return addr;
                    }
                } else if(!addr.isSiteLocalAddress()
                        && !addr.isLoopbackAddress()) {
                    return addr;
                }
            }
        }
        return null;
    }
    
    private String getInsideAddr(String insideAddr) {
        if(!StringUtil.isEmpty(insideAddr)) {
            return insideAddr;
        }
        List<InetAddress> addrs = IPUtil.getIPList();
        InetAddress addr = getSuitableAddr(addrs, Inet4Address.class, true);//优先用内网IPv4
        if(addr == null) {
            addr = getSuitableAddr(addrs, Inet6Address.class, true);
        }
        
        if(addr == null) {
            printHelp(help(), new String[] {
                "Can't get a valid internal ip address",
                "You should input the paramter"
            });
            return null;
        }
        return addr.getHostAddress();
    }
    
    private boolean changePassword(String[] args) throws Exception {
        if(args.length < 2) {
            printHelp(help());
            return false;
        }

        CompanyInfo localCompany = getLocalCompany();
        HandleResult hr = localCompany.changePwd(args[0], args[1]).get(10, TimeUnit.SECONDS);
        System.out.println("Change password,result:{}" + hr.brief());
        return hr.code == RetCode.OK;
    }

    private boolean publicKey(String[] args) throws Exception {
        CompanyInfo localCompany = getLocalCompany();
        System.out.println(localCompany.authKeyPair().publicKey2Str());
        return true;
    }
    
    private boolean changeAuth(String[] args) throws Exception {
        CompanyInfo localCompany = getLocalCompany();
        localCompany.changeAuth(args[0]);
        return true;
    }
    
    private CompanyInfo getLocalCompany() throws MeshException {
        CompanyInfo localCompany = CompanyInfo.instance();
        if(!localCompany.isValid()) {
            System.out.println("failed,haven't logined,use login command to do it");
            throw new MeshException("Fail to get local company info");
        }

        return localCompany;
    }
    
    private boolean accessCode(String[] args) throws Exception {
        if(args.length < 1) {
            CompanyInfo localCompany = getLocalCompany();
            System.out.println(localCompany.accessCode());
            return true;
        }

        if(args[0].equals("generate")) {
            String code = StringUtil.genRandomCode(8);
            System.out.println("plain:" + code + ",encoded:" + SecureUtil.sha256(code));
            return true;
        }
        
        CompanyInfo localCompany = getLocalCompany();
        localCompany.saveToHttpdns(null, null, args[0]).whenComplete((hr, e) -> {
            if(e != null) {
                System.out.println("Fail to set accesscode:" + e.getMessage());
                return;
            }
            if(hr.code != RetCode.OK) {
                System.out.println("failed:" + hr.brief());
            } else {
                System.out.println("succeeded,valid it by restarting the server");
            }
        }).get(10, TimeUnit.SECONDS);

        return true;
    }
    
    private boolean setInfo(String[] args) throws Exception {
        if(args.length < 1) {
            printHelp(help());
            return false;
        }

        CompanyInfo localCompany = getLocalCompany();
        String name = args[0];
        String country = args.length > 1 ? args[1] : localCompany.country();
        String province = args.length > 2 ? args[2] : localCompany.province();
        String city = args.length > 3 ? args[3] : localCompany.city();
        String county = args.length > 4 ? args[4] : localCompany.city();
        String info = args.length > 5 ? args[5] : localCompany.info();
        
        localCompany.setInfo(name, country, province, city, county, info).whenComplete((hr, e) -> {
            if(e != null) {
                System.out.println("Fail to set company info:" + e.getMessage());
                return;
            }
            if(hr.code != RetCode.OK) {
                System.out.println("failed:" + hr.brief());
            } else {
                System.out.println("succeeded");
                System.out.println("name:" + name);
                System.out.println("country:" + country);
                System.out.println("province:" + province);
                System.out.println("city:" + city);
                System.out.println("info:" + info);
            }
        }).get(10, TimeUnit.SECONDS);
        return true;
    }
    
    private boolean info(String[] args) throws Exception {
        CompanyInfo localCompany = getLocalCompany();
        System.out.println("id: " + localCompany.id);
        System.out.println("name: " + localCompany.name());
        System.out.println("inside addr: " + localCompany.insideAddr);
        System.out.println("outside addr: " + localCompany.outsideAddr());
        System.out.println("credit code: " + localCompany.creditCode);
        System.out.println("access code: " + localCompany.accessCode());
        System.out.println("country: " + localCompany.country());
        System.out.println("province: " + localCompany.province());
        System.out.println("city: " + localCompany.city());
        System.out.println("county: " + localCompany.county());
        System.out.println("auth public key: " + localCompany.authKeyPair().publicKey2Str());
        return true;
    }
    
    private boolean backupAt(String[] args) throws Exception {
        try {
            if(args.length > 1) {
                CompanyInfo localCompany = getLocalCompany();
                int backupAt = ValParser.parseInt(args[0], -1);
                if(backupAt < 0) {
                    System.out.println("Invalid backupAt value:" + args[0]);
                    return false;
                }
                localCompany.setBackupAt(backupAt).whenComplete((hr, e) -> {
                    if(e != null) {
                        System.out.println("Fail to set backupAt:" + e.getMessage());
                        return;
                    }
                    System.out.println("set backupAt to " + backupAt);
                }).get(10, TimeUnit.SECONDS);
            } else {
                CompanyInfo localCompany = getLocalCompany();
                localCompany.getBackupAt().whenComplete((hr, e) -> {
                    if(e != null) {
                        System.out.println("Fail to get backupAt:" + e.getMessage());
                        return;
                    }
                    if(hr.code != RetCode.OK) {
                        System.out.println("Fail to get backupAt:" + hr.brief());
                        return;
                    }
                    int backupAt = ValParser.getAsInt(hr.data, "backupAt");
                    long recent = ValParser.getAsLong(hr.data, "recent");
                    long nextBackup = DateUtil.recentNextTime(PeriodType.DAY, 0, backupAt * 60000);
                    System.out.println("backupAt:" + backupAt
                            + ",recent:" + DateUtil.utcToLocale(recent)
                            + ",nextBackupAt:" + DateUtil.utcToLocale(nextBackup));
                }).get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }
    
    private boolean setTotp(String[] args) {
        try {
            CompanyInfo ci = getLocalCompany();
            ServiceReqBuilder builder = ServiceClient.backendReqBuilder(IConst.SERVICE_BIOS)
                    .url("/keys/setTotpPwd")
                    .token(ci.adminToken(IConst.SERVICE_BIOS).generate())
                    .cid(ci.id)
                    .nodeId(ci.id);
            HandleResult hr = BiosClient.put(builder).get(10, TimeUnit.SECONDS);
            if(hr.code != RetCode.OK) {
                System.out.println(hr.brief());
            } else {
                String pwd = ValParser.getAsStr(hr.data, "pwd");
                System.out.println("pwd " + pwd);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }
    
    //使用totp根密钥产生totp密码
    private boolean getTotp(String[] args) throws Exception {
        int timeStep = CMD_TOTP_TIMESTEP;
        if(args.length < 1) {
            File cfgFile = new File(FileUtil.addPath(configDir, "totp.key"));
            Totp totp = new Totp(Totp.DEFAULT_CODE_DIGITS, timeStep);
            long leftTime = timeStep - (System.currentTimeMillis() / 1000 - totp.currentTime() * timeStep);
            if(cfgFile.exists()) { //本地有文件，则用文件中的密钥计算，否则从bios中获取
                String pwd = FileUtil.readFile(cfgFile, IConst.DEFAULT_CHARSET);
                System.out.println(totp.generateCode(pwd) + ",left time:" + leftTime);
                return true;
            }

            try {
                CompanyInfo ci = getLocalCompany();
                //bios中密码是最准确的
                ServiceReqBuilder builder = ServiceClient.backendReqBuilder(IConst.SERVICE_BIOS)
                        .url("/keys/getTotpPwd")
                        .token(ci.adminToken(IConst.SERVICE_BIOS).generate())
                        .cid(ci.id)
                        .nodeId(ci.id);
                HandleResult hr = BiosClient.get(builder).get(10, TimeUnit.SECONDS);
                if(hr.code != RetCode.OK) {
                    System.out.println(hr.brief());
                    return false;
                }
                String pwd = ValParser.getAsStr(hr.data, "key");
                System.out.println(totp.generateCode(pwd) + ",left time:" + leftTime);
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }
        
        int codeLen = Totp.DEFAULT_CODE_DIGITS;
        if(args.length > 1) {
            codeLen = ValParser.parseInt(args[1], Totp.DEFAULT_CODE_DIGITS);
        }
        
        if(args[0].equalsIgnoreCase("generate")) {
            System.out.println(Totp.generateSecret(codeLen));
        }

        if(args.length > 2) {
            codeLen = ValParser.parseInt(args[2], Totp.DEFAULT_CODE_DIGITS);
        }

        Totp totp = new Totp(codeLen, timeStep);
        long leftTime = timeStep - (System.currentTimeMillis() / 1000 - totp.currentTime() * timeStep);
        System.out.println(totp.generateCode(args[0]) + ",left time:" + leftTime);
        
        return true;
    }
}
