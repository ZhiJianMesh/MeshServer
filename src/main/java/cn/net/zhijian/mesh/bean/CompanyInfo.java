package cn.net.zhijian.mesh.bean;

import java.io.File;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.client.HttpClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
//import cn.net.zhijian.mesh.frm.abs.AbsRootKey;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig.DeployMode;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.mesh.frm.tokenworker.EccTokenWorker;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.IPUtil;
import cn.net.zhijian.util.IPUtil.FORMAT;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 每个实例中都必须有一个CompanyInfo，记录实例从属的公司信息。
 * 1）公有云：preference中只记录0号公司，其他公司信息从company服务加载
 *    A）数据根密钥使用rootkey加密后，存在keystore中；
 *    B）无需认证密钥对，不存在company的token，即使服务接口定义了company鉴权，
         也会转成user鉴权，在COMPANY类中处理此逻辑。
 * 2）私有云：需加载完整的公司信息，具有独立的id、独立的安全策略；
 *    A）数据根密钥使用自身的rootkey加密后存在preference中，
 *      同时使用公司登录密钥加密后存到云上keystore中，以做备份；
 *    B）需要认证密钥对，公钥存在云上company服务中，
 *      getServiceToken时，传递isCloud获得company token，
 *      携带此token访问云侧服务，被调服务访问company.verify认证token；
 *    C)端侧调用私有云维护接口，先用公司密码换取公司token，再用此token
 *      访问私有云服务器
 * @author flyinmind of csdn.net
 */
public class CompanyInfo {
    /*
     * PDR:私有云数据根密钥，此密钥是用登录密码加密的，首次注册或登录时创建它，
     * 以后不可变更，每次登录都从云侧获取最初创建的，用于加解密数据密钥。
     * 私有云侧存在company.cfg中，不重装，则不用重新从云侧恢复。
     * CDR：公有云数据根密钥，此密钥不能用公司密码加密，因为云侧需要读取
     */
    public static final String DATAROOT_INCLOUD = "CDR";
    public static final String DATAROOT_INPRIVATE = "PDR";
    public static final String ENVROOT_INPRIVATE = "EVR";

    public static final String ITEM_NAME = "name";
    public static final String ITEM_AREA = "area";
    public static final String ITEM_DBNO = "dbNo";
    public static final String ITEM_CREDITCODE = "creditCode";

    private static final String ITEM_COMPANYID = "id";
    private static final String ITEM_AUTHKEY = "authKey";
    private static final String ITEM_DNSACCESSCODE = "dnsAccessCode";
    private static final String ITEM_INSIDEADDR = "insideAddr";
    private static final String ITEM_OUTSIDEADDR = "outsideAddr";
    private static final String ITEM_RUNMODE = "runMode";
    
    public static final String ITEM_COUNTY = "county";
    public static final String ITEM_COUNTRY = "country";
    public static final String ITEM_PROVINCE = "province";
    public static final String ITEM_CITY = "city";
    public static final String ITEM_INFO = "info";
    public static final String ITEM_BACKUPAT = "backupAt"; //数据库备份时间点
    
    private static final long COMPANY_TOKEN_EXPIRES_IN = 86400L * 1000L; //1 day
    private static final Logger LOG = LogUtil.getInstance();
    
    //公司服务运行方式，公司网络可以是自建的私有云，或在公有云上搭建
    //与PartitionConfig中的runmode不同，partition中的runmode是组网方式
    public enum RunMode {
        SINGLETON, //公司网络单例部署，只有一个实例，此时会自动初始化数据库
        CLUSTER, /* 集群部署，集群模式(默认)，有多个实例共同工作，此时不会自动化初始数据库，
                    安装时由执行的实例初始化数据库，
                    即使部署在公有云上，只要不是至简网格的集群，都是私网集群，
                    需要设置outside、inside */
        ROOT; //根环境部署，在至简网格的服务端运行，outside与inside都是外网地址

        public static RunMode getValue(String name) {
            String s = StringUtil.isEmpty(name) ? IConst.EMPTY_STR : name.toUpperCase();
            if(CLUSTER.name().equals(s)) {
                return CLUSTER;
            }
            if(ROOT.name().equals(s)) {
                return ROOT;
            }
            return SINGLETON;
        }
    }

    /* 0号公司即为平台本身，在单例模式中，一个实例只为一个公司服务，
     * 公有云实例中，一个实例中会处理所有公司的请求，
     * 一个webdb实例会存储多家公司的数据，通过dbNo来区分
     */
    public final int id;
    public final String creditCode; //公司统一信用码
    public final RunMode mode;

    private String name;
    private String country; //所属国家
    private String province; //省份
    private String city; //城市
    private String county; //县/区
    private String info; //描述信息

    //内部入口地址，用于访问/prob、/lookup接口，在公有云部署，则与outsideAddr相同
    //私网单例的情况，自动上报到httpdns中，集群的情况手动设置
    public final String insideAddr;
    //外部入口地址，可以与insideAddr一样，或为空
    //与insideAddr同时上报到httpdns
    private String outsideAddr = IConst.EMPTY_STR;

    private volatile int area;
    private volatile int dbNo; //数据库编号，用以确定可以访问哪个数据库实例
    //公司级密钥对，公钥在注册公司时上报到公有云，私钥用于生成访问公有云的帐号token
    EccTokenWorker tokenWorker = null;

    String accessCode = IConst.EMPTY_STR; //接入码，用于访问/prob、/lookup接口
    /*
     * 公司密钥对用于与公有云建立信任关系，比如查询版本升级，访问公有云侧服务等。
     * 公司注册或登录时，publicKey要传递倒公有云中，私钥保存在本地，
     * 当此密钥对丢失时，需要通过公司注册时的密码，重新将publicKey通知给公有云，
     * 否则，访问公有云服务时，将会失败
     */
    EccKeyPair authKeyPair = null; //私有云用户访问公网的密钥对
    

    //每个实例都有自己的company.cfg，其中必须有公司信息
    //在启动时必须加载，数量极少，通常1个，所以不用map存储
    private static volatile CompanyInfo instance;
    private static File cfgFile;
    private static final CompanyInfo NULL_COMPANY = new CompanyInfo(IConst.NULL_COMPANY_ID,
            RunMode.SINGLETON, IConst.EMPTY_STR, IConst.EMPTY_STR,
            IConst.EMPTY_STR, IConst.EMPTY_STR, IConst.EMPTY_STR,
            IConst.EMPTY_STR, IConst.EMPTY_STR, IConst.EMPTY_STR);

    /**
     * 绝大部分情况只有一个公司信息，直接返回第0个
     * 多个公司的情况，第一个公司信息就是首要公司，其他公司的父id就设置成它
     * @return 公司信息
     */
    
    public static CompanyInfo instance() {
        //不会返回空
        return instance == null ? NULL_COMPANY : instance; //必须先调用init
    }
    
    public static File configFile(String confPath) {
        return new File(FileUtil.addPath(confPath, "company.cfg"));
    }
    
    /**
     * 加载本地公司配置，私有云中加载注册的公司，公有云加载0号公司。
     * 所以，服务实例启动时，必然有一个localCompany
     * @param cfg 配置信息
     * @return 配置的公司信息
     */
    static CompanyInfo parse(Map<String, Object> cfg) throws MeshException {
        int cid = ValParser.getAsInt(cfg, ITEM_COMPANYID, IConst.NULL_COMPANY_ID);
        if(cid <= IConst.LOCAL_COMPANY_ID) {
            throw new MeshException("Invalid CompanyInfo.cid:" + cid);
        }
        String name = ValParser.getAsStr(cfg, ITEM_NAME, IConst.EMPTY_STR);
        String creditCode = ValParser.getAsStr(cfg, ITEM_CREDITCODE, IConst.EMPTY_STR);
        String country = ValParser.getAsStr(cfg, ITEM_COUNTRY, IConst.EMPTY_STR);
        String province = ValParser.getAsStr(cfg, ITEM_PROVINCE, IConst.EMPTY_STR);
        String city = ValParser.getAsStr(cfg, ITEM_CITY, IConst.EMPTY_STR);
        String county = ValParser.getAsStr(cfg, ITEM_COUNTY, IConst.EMPTY_STR);
        String info = ValParser.getAsStr(cfg, ITEM_INFO, IConst.EMPTY_STR);
        String dnsAccessCode = secureRead(cfg, ITEM_DNSACCESSCODE, IConst.EMPTY_STR);
        RunMode mode = RunMode.getValue(ValParser.getAsStr(cfg, ITEM_RUNMODE));
        
        String insideAddr = ValParser.getAsStr(cfg, ITEM_INSIDEADDR, IConst.EMPTY_STR);
        if(StringUtil.isEmpty(insideAddr)) {
            throw new MeshException("Invalid CompanyInfo.insideAddr:" + cid);
        }
        String outsideAddr = ValParser.getAsStr(cfg, ITEM_OUTSIDEADDR, null);

        PartitionConfig partCfg = PartitionConfig.instance();
        if(partCfg.isInCloud) {
            insideAddr = outsideAddr; //云上环境，内外网必须相同，否则注册后端侧无法访问
        }

        EccKeyPair authKeyPair = readKeyPair(cfg, ITEM_AUTHKEY);
        CompanyInfo ci = new CompanyInfo(cid, mode, name, creditCode, country, province, city, county, info, insideAddr);
        ci.setOutsideAddr(outsideAddr);
        ci.area = ValParser.getAsInt(cfg, ITEM_AREA, -1);
        ci.dbNo = ValParser.getAsInt(cfg, ITEM_DBNO, IDBConst.LOCAL_DBNO);
        ci.accessCode = dnsAccessCode;
        ci.setAuthKeyPair(authKeyPair, false);

        return ci;
    }
    
    public static void reInit() throws MeshException {
        if(cfgFile == null || !cfgFile.exists()) {
            throw new MeshException("not parsed from company.cfg");
        }
        init(cfgFile);
    }

    public static void init(File cfgFile) throws MeshException {
        Map<String, Object> companyCfg = JsonUtil.jsonFileToMap(cfgFile, true);
        if(companyCfg == null || companyCfg.isEmpty()) {
            throw new MeshException("invalid company config " + cfgFile);
        }

        Map<String, Object> cfg = ValParser.parseObject(companyCfg);
        if(cfg == null || cfg.isEmpty()) {
            LOG.error("Invalid company config {}", companyCfg);
            throw new MeshException("Invalid company config");
        }
        CompanyInfo ci = parse(cfg);
        if(StringUtil.isEmpty(ci.accessCode)) {
            LOG.error("accessCode is null, fail to load company {}", ci.id);
            throw new MeshException("No accessCode in company " + ci.id);
        }
        
        if(ci.authKeyPair == null) {
            /*
             * keypair发生变更时，在注册或重新登录时才会更新到company服务中，
             * 在此之前，私有云用户无法访问公有云服务。
             * 【注意】在跟环境的公司级服务无此操作，
             * user的getServieToken返回USER类token；
             * verify时，COMPANY的token检查自动转为USER类token检查
             */
            LOG.error("authKeyPair is null, fail to load company {}", ci.id);
            throw new MeshException("No authKey in company " + ci.id);
        }
        CompanyInfo.instance = ci;
        CompanyInfo.cfgFile = cfgFile;
    }

    /**
     * 将公司信息注册到公有云，用在安卓版本的JsServer中
     * 公司级服务都在公有云上，在OM中完成，则没有注册操作
     * @param creditCode 统一信用码
     * @param pwd 密码
     * @param name 名称
     * @param country 国家码
     * @param province 省
     * @param city 城市
     * @param county 县/地区
     * @param info 其他信息
     * @param verifyCode 验证码
     * @param session 验证码会话
     * @param insideAddr 内网地址
     * @param outsideAddr 外网地址
     */
    public static CompletableFuture<HandleResult> register(
            String creditCode, String pwd, String cfmPwd, String name,
            String country, String province, String city, String county,
            String info, String session, String verifyCode,
            String insideAddr, String outsideAddr) {
        if(!Pattern.matches("[\\w|_!@#$%^&*]{4,20}", pwd) || !cfmPwd.equals(pwd)) {
            return HandleResult.future(RetCode.WRONG_PARAMETER, "invalid password");
        }

        String inAddr;
        PartitionConfig partCfg = PartitionConfig.instance();
        if(partCfg.isInCloud) { //部署在根环境，内外网地址一样
            if(StringUtil.isEmpty(outsideAddr)
               || IPUtil.isValidIP(outsideAddr, FORMAT.WAN.v + FORMAT.PORT.v) != FORMAT.NONE) {
                return HandleResult.future(RetCode.WRONG_PARAMETER, "invalid outsideAddr");
            }
            inAddr = outsideAddr;
        } else { //部署在私有网络，可以没有外网地址，如果设置了则必须有效
            if(StringUtil.isEmpty(insideAddr)
                || IPUtil.isValidIP(insideAddr, FORMAT.LAN.v + FORMAT.PORT.v) != FORMAT.NONE) {
                 return HandleResult.future(RetCode.WRONG_PARAMETER, "invalid insideAddr");
             }
            inAddr = insideAddr;
             if(!StringUtil.isEmpty(outsideAddr)
                && IPUtil.isValidIP(outsideAddr, FORMAT.WAN.v + FORMAT.PORT.v) != FORMAT.NONE) {
                 return HandleResult.future(RetCode.WRONG_PARAMETER, "invalid outsideAddr");
             }            
        }

        RunMode mode = partCfg.mode == PartitionConfig.DeployMode.CLUSTER ? RunMode.CLUSTER : RunMode.SINGLETON;
        String authKeyStr;
        EccKeyPair authKey;
        Ecc ecc = Ecc.instance();
        try {
            authKey = ecc.genKeyPair();
            authKeyStr = authKey.toString(pwd);
        } catch (Exception e) {
            LOG.error("Fail to create new authKeyPair", e);
            return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to create key pair");
        }
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
           .url("/company/register")
           .traceId("register_" + creditCode)
           .cid(IConst.LOCAL_COMPANY_ID) //并无实际意义，只是为了保证必须有cid
           .put("creditCode", creditCode)
           //在平台中不可能知道公司密码，因为传递到平台时已sha256
           .put("pwd", SecureUtil.sha256(pwd))
           .put("cfmPwd", SecureUtil.sha256(cfmPwd))
           .put("name", name)
           .put("partition", PartitionConfig.PRIVATE_CLOUD_PARTITION)
           .put("country", country)
           .put("province", province)
           .put("city", city)
           .put("county", county)
           .put(ITEM_RUNMODE, mode.name())
           .put(ITEM_AUTHKEY, authKeyStr) //私有环境运行时，私钥加密
           .put("verifyCode", verifyCode)
           .put("session", session)
           .put("info", info);
        return ServiceClient.cloudPost(req).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
               LOG.error("Fail to register, result:{}", hr.brief());
               LOG.info("Request data:{}", req.data());
               return CompletableFuture.completedFuture(hr);
            }
            int cid = ValParser.getAsInt(hr.data, "cid");
            byte[] caBytes = ByteUtil.base642bin(ValParser.getAsStr(hr.data, "ca", IConst.EMPTY_STR));
            LOG.debug("Success to register {},cid:{},creditCode:{},runMode:{}",
                    name, cid, creditCode, mode.name());
            
            CompanyInfo old = CompanyInfo.instance();
            CompanyInfo ci = new CompanyInfo(cid, mode, name, creditCode,
                    country, province, city,
                    IConst.EMPTY_STR, info, inAddr);
            ci.setOutsideAddr(outsideAddr);
            
            if(old != null) { //尽量使用以前的，避免影响每个成员
                ci.accessCode = old.accessCode;
            } else { //卸载安装后重新login的情况，company为空，此时端侧都需要更新接入码
                ci.accessCode = StringUtil.genRandomCode(8);
            }
            ci.setAuthKeyPair(authKey, false);
            ServerSecurity.saveServerCA(caBytes);//保存ca，用于安卓服务器创建https
            
            String dataRootKp, envRootKey;
            try {
                EccKeyPair ekp = Ecc.instance().genKeyPair();
                dataRootKp = ekp.toString(pwd);
                RootKeystore.init(); //已存在，则使用老的，多个公司共用服务器，都使用相同的
                envRootKey = RootKeystore.instance().encodeKeyStore(pwd);
            } catch (Exception ex) {
                LOG.error("Fail to generate data root keypair", ex);
                return HandleResult.future(RetCode.CLIENT_ERROR, "fail to create root keypairs");
            }
            String token = ci.adminToken(IConst.SERVICE_KEYSTORE).generate();
            return ci.saveRootKeys(token, dataRootKp, envRootKey).thenComposeAsync(result -> {
                if(result.code != RetCode.OK) {
                    LOG.error("Fail to saveDataKeypair in register,result:{}", result.brief());
                    return CompletableFuture.completedFuture(result);
                }
                LOG.debug("Success to saveDataRootKey {}.{}.{} in register", name, cid, creditCode);
                CompanyInfo.instance = ci;
                return ci.registerToHttpdns(); //更新入口信息
            }, IThreadPool.Pool);
        }, IThreadPool.Pool);
    }
    
    /**
     * 在私有云，使用id与密码登录公司信息，过程中会更新认证公钥，
     * 如果在主实例注册过，已经有company.cfg，所以此函数用于重建BIOS主实例，
     * BIOS从实例上也需要login
     * 公司级服务都在公有云上，没有登录操作
     * @param id 公司id
     * @param fid 母公司id
     * @param pwd 密码
     * @param inside 内网入口地址
     * @param outside 外网入口地址
     */
    public static CompletableFuture<HandleResult> login(int id, int fid, String pwd, String inside, String outside) {
        if(!Pattern.matches("\\w{4,20}", pwd)) {
            return HandleResult.future(RetCode.WRONG_PARAMETER, "invalid password");
        }
        
        if(StringUtil.isEmpty(inside)
           || IPUtil.isValidIP(inside, FORMAT.LAN.v + FORMAT.PORT.v) != FORMAT.NONE) {
            return HandleResult.future(RetCode.WRONG_PARAMETER, "invalid insideAddr");
        }

        if(!StringUtil.isEmpty(outside)
           && IPUtil.isValidIP(outside, FORMAT.WAN.v + FORMAT.PORT.v) != FORMAT.NONE) {
            return HandleResult.future(RetCode.WRONG_PARAMETER, "invalid outsideAddr");
        }

        PartitionConfig partCfg = PartitionConfig.instance();
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
                .url("/company/login")
                .traceId("login_" + id)
                .cid(id)
                .put("id", id)
                .put("fid", fid)
                .put("pwd", SecureUtil.sha256(pwd))
                .put(ITEM_RUNMODE, partCfg.mode.name());

        CompanyInfo oldOne = CompanyInfo.instance();
        String insideAddr;
        String outsideAddr;
        if(oldOne != null) { //登录过，重新登录，比如刷新本地的rootkey
            req.put(ITEM_AUTHKEY, oldOne.authKeyPair.toString(pwd));
            outsideAddr = oldOne.outsideAddr;
            insideAddr = oldOne.insideAddr;
        } else {
            insideAddr = inside;
            outsideAddr = outside;
        }
        
        return ServiceClient.cloudPost(req).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to login company {}, result:{}", id, hr.brief());
                return CompletableFuture.completedFuture(hr);
            }
            int cid = ValParser.getAsInt(hr.data, "cid");

            String name = ValParser.getAsStr(hr.data, ITEM_NAME);
            
            String creditCode = ValParser.getAsStr(hr.data, ITEM_CREDITCODE);
            String country = ValParser.getAsStr(hr.data, ITEM_COUNTRY);
            String province = ValParser.getAsStr(hr.data, ITEM_PROVINCE);
            String city = ValParser.getAsStr(hr.data, ITEM_CITY);
            String county = ValParser.getAsStr(hr.data, ITEM_COUNTY);
            String info = ValParser.getAsStr(hr.data, ITEM_INFO);
            RunMode mode = RunMode.getValue(ValParser.getAsStr(hr.data, ITEM_RUNMODE));
            
            CompanyInfo old = CompanyInfo.instance();
            CompanyInfo ci = new CompanyInfo(cid, mode, name, creditCode,
                    country, province, city, county, info, insideAddr);
            ci.area = ValParser.getAsInt(hr.data, "area");
            ci.dbNo = ValParser.getAsInt(hr.data, "dbNo");
            ci.setOutsideAddr(outsideAddr);
            if(!ServerSecurity.configFile().exists()) {
                String caStr = ValParser.getAsStr(hr.data, "ca", IConst.EMPTY_STR);
                byte[] caBytes = ByteUtil.base642bin(caStr);
                ServerSecurity.saveServerCA(caBytes);//保存ca，用于安卓服务器创建https
            }
            
            if(old != null) { //尽量使用原来的
                ci.accessCode = old.accessCode;
            } else { //注册过，卸载后再安装，会出现company为空的现象
                ci.accessCode = StringUtil.genRandomCode(8);
            }
            
            //根环境中运行的公司服务，数据根密钥、认证密钥都是明文存储
            //私有环境中运行的公司，数据根密钥、认证密钥用公司密码加密
            String authKeyInCloud = ValParser.getAsStr(hr.data, ITEM_AUTHKEY);
            EccKeyPair authKpInCloud = null;
            try {
                //只有非ROOT环境的公司才会login，所以此处不用判断mode是否为ROOT
                authKpInCloud = EccKeyPair.parse(authKeyInCloud, pwd);
            } catch(Exception e) {
                LOG.error("Fail to parse authkey from cloud", e);
            }
            
            LOG.debug("Success to login {} with {} mode,cid:{},authKey:{}",
                    name, mode.name(), cid, authKeyInCloud);
            CompletableFuture<HandleResult> cf;
            if(authKpInCloud == null || authKpInCloud.prv == null) {
                try {
                    LOG.info("Invalid authKey from server,reset it");
                    ci.setAuthKeyPair(Ecc.instance().genKeyPair(), false);
                    cf = ci.changeAuth(pwd);
                } catch (Exception e) {
                    LOG.error("Fail to create local authkey for comapny {}", id, e);
                    return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to create authkey");
                }
            } else {
                ci.setAuthKeyPair(authKpInCloud, false);
                cf = CompletableFuture.completedFuture(HandleResult.OK);
            }

            return cf.thenComposeAsync(r -> {
                if(hr.code != RetCode.OK) {
                    LOG.error("Fail to changeAuth(),result:{}", r.brief());
                    return HandleResult.future(r);
                }
                String token = ci.adminToken(IConst.SERVICE_KEYSTORE).generate();
                return ci.initRootKey(pwd, token).thenComposeAsync(r1 -> {
                    if(r1.code != RetCode.OK) {
                        LOG.error("Fail to initRootKey() when login,result:{}", r1.brief());
                        return HandleResult.future(r1);
                    }
                    CompanyInfo.instance = ci;
                    return ci.registerToHttpdns(); //更新入口信息
                }, IThreadPool.Pool);
            }, IThreadPool.Pool);
        }, IThreadPool.Pool);
    }

    public static CompletableFuture<HandleResult> login(int id, String pwd, String inside, String outside) {
        return login(id, IConst.NULL_COMPANY_ID, pwd, inside, outside);
    }
    
    private CompletableFuture<HandleResult> initRootKey(String pwd, String token) {
        return getRootKey(token, ENVROOT_INPRIVATE).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to initRootKey(),result:{},save local rootkey to keystore", hr.brief());
                return saveRootKey(pwd, token);
            }
            
            try {
                String envRootKs = ValParser.getAsStr(hr.data, "kp");
                //解密后存入本地，后面启动无需重新下载
                RootKeystore rks = RootKeystore.parse(envRootKs, pwd);
                RootKeystore.init(rks.getBinaryKey());
            } catch(Exception e) {
                LOG.error("Fail to parse rootkey from cloud,reset it", e);
                return saveRootKey(pwd, token);
            }

            return HandleResult.future(hr);
        }, IThreadPool.Pool);        
    }

    private CompletableFuture<HandleResult> saveRootKey(String pwd, String token) {
        try {
            String rootKey = RootKeystore.instance().encodeKeyStore(pwd); //如果不存在会创建
            return saveRootKey(token, rootKey, ENVROOT_INPRIVATE);
        } catch (Exception e) {
            LOG.error("Fail to load local root key", e);
            return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to load local root key");
        }
    }
    /**
     * 将公司的运行模式改为私有云模式
     * @param pwd 公司登录密码
     */
    public CompletableFuture<HandleResult> moveToPrivate(String pwd) {
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_KEYSTORE)
                .url("/company/moveToPrivate")
                .traceId("moveToPrivate_" + id)
                .cid(id)
                .token(adminToken(IConst.SERVICE_KEYSTORE).generate())
                .put(ITEM_AUTHKEY, authKeyPair().toString(pwd)); //私有环境，私钥加密，公钥公开
        return ServiceClient.cloudPut(req).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to moveToPrivate, result:{}", hr.brief());
                return CompletableFuture.completedFuture(hr);
            }
            String token = adminToken(IConst.SERVICE_KEYSTORE).generate();
            return getRootKeys(token, new String[]{DATAROOT_INCLOUD, ENVROOT_INPRIVATE}).thenComposeAsync(r -> {
                if(r.code != RetCode.OK) {
                    LOG.error("Fail to getRootKeys when moveToPrivate,result:{}", r.brief());
                    return HandleResult.future(r);
                }
                
                EccKeyPair eccDataKp;
                String dataRootKp, envRootKs;

                try {
                    dataRootKp = ValParser.getAsStr(hr.data, DATAROOT_INCLOUD);
                    eccDataKp = EccKeyPair.parse(dataRootKp); //原先ROOT方式运行的，数据密钥不加密
                     dataRootKp = eccDataKp.toString(pwd);
                    envRootKs = ValParser.getAsStr(hr.data, ENVROOT_INPRIVATE);
                    
                    RootKeystore rks;
                    if(StringUtil.isEmpty(envRootKs)) {//omregister注册的不一定有ENVROOT_INPRIVATE
                        rks = RootKeystore.create();
                    } else {
                        rks = RootKeystore.parse(envRootKs, pwd);
                    }
                    envRootKs = rks.encodeKeyStore(pwd);
                } catch(Exception e) {
                    LOG.error("Fail to parse keypair", e);
                    return HandleResult.future(RetCode.DATA_WRONG, "invalid keypair from cloud");
                }
                //设置完成后，立刻使用公司密码加密后再存到云上，防止丢失
                return saveRootKeys(token, dataRootKp, envRootKs);
            }, IThreadPool.Pool);
        }, IThreadPool.Pool);
    }

    private CompanyInfo(int cid, RunMode mode, String name, String creditCode,
            String country, String province, String city,
            String county, String info, String insideAddr) {
        this.id = cid;
        this.mode = mode;
        this.name = name;
        this.creditCode = creditCode;
        this.country = country;
        this.province = province;
        this.city = city;
        this.county = county;
        this.info = info;
        this.insideAddr = insideAddr;
    }
    
    /**
     * 设置与公有云服务对应的密钥对，此密钥对用于产生访问公有云服务的token。
     * 安卓中是在application中调用此函数，根据平台不同，需要在不同地方调用它
     * @param kp 密钥对
     * @param saveIt 是否保存
     */
    public void setAuthKeyPair(EccKeyPair kp, boolean saveIt) {
        if(kp == null) {
            return;
        }
        authKeyPair = kp;
 
        LOG.debug("Auth public key:{}", kp.publicKey2Str());
        tokenWorker = new EccTokenWorker(kp, IOAuth.SIGNTYPE_COMPANYKEY);
        if(saveIt) {
            save();
        }
    }

    public EccKeyPair authKeyPair() {
        if(authKeyPair == null) {
            LOG.debug("No auth key pair,generate a new one");
            Ecc ecc = Ecc.instance();
            try {
                EccKeyPair kp = ecc.genKeyPair();
                setAuthKeyPair(kp, true);
            } catch (Exception e) {
                LOG.error("Fail to create new authKeyPair", e);
                return null;
            }
        }
        return authKeyPair;
    }

    public ITokenWorker tokenWorker() {
        return tokenWorker;
    }

    public CompletableFuture<HandleResult> setBackupAt(int at) {
        ServiceReqBuilder builder = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
                .url("/webdb/setBackupAt")
                .put(ITEM_BACKUPAT, at)
                .token(adminToken(IConst.SERVICE_COMPANY).generate())
                .cid(this.id)
                .nodeId(this.id);
        if(isRoot()) {
            return ServiceClient.servicePut(builder); //在根环境，company是本地服务
        }
        return ServiceClient.cloudPut(builder); //在私有环境，调用根环境的company服务
    }
    
    /**
     * 查询公司的备份时间点，可以查询多个
     * @return 公司的备份时间点backupAt、最近备份时间recent
     */
    public CompletableFuture<HandleResult> getBackupAt() {
        ServiceReqBuilder builder = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
                .url("/webdb/getBackupAt")
                .token(adminToken(IConst.SERVICE_COMPANY).generate())
                .cid(this.id)
                .nodeId(this.id);
        if(this.isRoot()) {
            return ServiceClient.serviceGet(builder); //根环境，company是本地服务
        }
        return ServiceClient.cloudGet(builder); //私有云环境，company是云上服务
    }
    
    public int area() {
        return area;
    }
    
    public void setArea(int area) {
        this.area = area;
    }
    
    public int dbNo() {
        return dbNo;
    }
    
    public void setDBNo(int dbNo) {
        this.dbNo = dbNo;
    }
    
    public boolean isRoot() {
        return id == IConst.ROOT_COMPANY_ID;
    }

    public String accessCode() {
        if(StringUtil.isEmpty(accessCode)) {
            accessCode = StringUtil.genRandomCode(8);
        }
        return accessCode;
    }

    /**
     * 设置外网网关地址，在使用长连接网关实现内网穿透时，
     * 当BridgeGW从公有云获得地址并成功连接后，就会调用setGwAddr，
     * 在端侧调用probe，判断req是来自外部，则返回的地址都是gwAddr
     * @param addr 网关地址
     */
    public void setOutsideAddr(String addr) {
        if(StringUtil.isEmpty(addr)) {
            outsideAddr = IConst.EMPTY_STR;
            return;
        }

        String ip = IPUtil.takeOffPort(addr);
        if(!IPUtil.isValidIP(ip) || IPUtil.isLanIp(ip)) {
            LOG.warn("Invalid outside address(`{}`) used", addr); //只告警，不打断
            outsideAddr = IConst.EMPTY_STR;
            return;
        }
        outsideAddr = addr;
    }

    public CompletableFuture<HandleResult> testOutsideAddr() {
        if(this.id <= IConst.LOCAL_COMPANY_ID || StringUtil.isEmpty(outsideAddr)) {
            return HandleResult.future();
        }
        ServiceReqBuilder builder = ServiceClient.backendReqBuilder(IConst.SERVICE_HTTPDNS)
                .url("/test?addr=" + outsideAddr)
                .token(adminToken(IConst.SERVICE_HTTPDNS).generate())
                .cid(this.id)
                .nodeId(this.id);
        return ServiceClient.cloudGet(builder);
    }
    
    public void updateOutsideAddr() {
        PartitionConfig partCfg = PartitionConfig.instance();
        if(partCfg.isInCloud) {
            return; //分区处于外网，不能更改外网地址，因为无法自动查询到外网ip
        }
        if(this.id <= IConst.LOCAL_COMPANY_ID || StringUtil.isEmpty(outsideAddr)) {
            return; //未登录公司或者未设置外网地址
        }
        String adminToken = adminToken(IConst.SERVICE_HTTPDNS).generate();
        List<CompletableFuture<HandleResult>> tasks = new ArrayList<>();
        List<String> reachable = new CopyOnWriteArrayList<>();
        List<InetAddress> ipList = IPUtil.getIPList(); //本机所有IP列表

        for(InetAddress ip : ipList) {
            String host = ip.getHostAddress();
            if(host == null || IPUtil.isLan(ip)) {
                continue;
            }
            String addr = IPUtil.hostFormat(host, ChannelConfig.instance().httpPort);
            ServiceReqBuilder builder = ServiceClient.backendReqBuilder(IConst.SERVICE_HTTPDNS)
                    .url("/test?addr=" + addr)
                    .token(adminToken)
                    .cid(id)
                    .nodeId(this.id);
            tasks.add(ServiceClient.cloudGet(builder).thenApplyAsync(hr -> {
                if(hr.code != RetCode.OK) {
                    LOG.warn("Fail to detect {},result:{}", addr, hr.brief());
                } else {
                    reachable.add(addr);
                }
                return hr;
            }, IThreadPool.Pool));
        }

        @SuppressWarnings("unchecked")
        CompletableFuture<HandleResult>[] taskArr = tasks.toArray(new CompletableFuture[0]);
        CompletableFuture.allOf(taskArr).thenApplyAsync(o -> {
            String checked = null;
            //都不可访问，也可能是根环境异常，则直接使用本地所有公网IP判断
            if(reachable.isEmpty()) {
                reachable.addAll(ipList.stream()
                        .map(InetAddress::getHostAddress)
                        .collect(Collectors.toList()));
            }
            for(String addr : reachable) {
                if(addr.equals(outsideAddr)) {
                    return outsideAddr; //尽量使用老的
                }
                checked = addr; //选用最后一个
            }
            return checked;
        }, IThreadPool.Pool).whenCompleteAsync((newOutsideAddr, e) -> {
            if(e != null) {
                LOG.error("Fail to test wan addrs", e);
                return;
            }

            //单例部署时，如果外部地址改变了，则上报一次，让客户端可以获得最新的
            //集群的情况，只能在注册或设置时改变
            if(partCfg.mode == DeployMode.SINGLETON && !outsideAddr.equals(newOutsideAddr)) {
                setOutsideAddr(newOutsideAddr);
                saveToHttpdns(null, newOutsideAddr, null);
            }
        }, IThreadPool.Pool);
    }

    public void checkOutsideAddr() {
        PartitionConfig partCfg = PartitionConfig.instance();
        if(partCfg.isInCloud) {
            return; //处于公网中，不用检测变化
        }
        if(this.id <= IConst.LOCAL_COMPANY_ID || StringUtil.isEmpty(outsideAddr)) {
            return; //未登录公司或者未设置外网地址
        }

        if(partCfg.mode == DeployMode.SINGLETON) {//单例模式才需要对端口操作
            if(StringUtil.isEmpty(outsideAddr)) { //无外网地址，直接关闭upnp端口
                ChannelConfig.instance().closePort();
            } else { //每次检查都重新打开一次，防止中途被人关闭
                ChannelConfig.instance().openPort();
            }
        }
        
        String newOutsideAddr = null;
        List<InetAddress> ipList = IPUtil.getIPList(); //本机所有IP列表

        for(InetAddress ip : ipList) {
            String host = ip.getHostAddress();
            if(host == null || IPUtil.isLan(ip)) {
                continue;
            }
            String addr = IPUtil.hostFormat(host, ChannelConfig.instance().httpPort);
            if(addr.equals(outsideAddr)) {
                newOutsideAddr = outsideAddr; //尽量使用老的
                break;
            }
            newOutsideAddr = addr; //否则选用最后一个
        }

        if(!outsideAddr.equals(newOutsideAddr)) { //改变了，则上报一次，让客户端可以获得最新的
            LOG.info("outsideAddr changed:{}->{}", outsideAddr, newOutsideAddr);
            setOutsideAddr(newOutsideAddr);
            registerToHttpdns();
        }
    }

    public String outsideAddr() {
        int fmt = IPUtil.FORMAT.WAN.v | IPUtil.FORMAT.PORT.v;
        return IPUtil.isValidIP(outsideAddr, fmt) == IPUtil.FORMAT.NONE ? outsideAddr : null;
    }
    
    /**
     * 将私网服务器的信息注册到httpdns服务中，
     * 使得端侧输入公司ID、接入码时，可以自动获得内网入口地址与外网入口地址。
     * 在启动服务、保证accessCode、保存外部地址时，会调用此函数
     * @return 异步执行结果
     */
    public CompletableFuture<HandleResult> registerToHttpdns() {
        if(id <= IConst.LOCAL_COMPANY_ID || StringUtil.isEmpty(insideAddr)) {
            return HandleResult.future(RetCode.INVALID_STATE, "company has not been set ok");
        }

        Map<String, Object> reqData = new HashMap<>();
        reqData.put("insideAddr", insideAddr);
        if(StringUtil.isEmpty(outsideAddr) || IPUtil.isLanIp(outsideAddr)) {
            outsideAddr = IConst.EMPTY_STR;
        }
        reqData.put("outsideAddr", outsideAddr);
        //公有云httpdns不宜存接入码，以免引起猜疑，所以存储SHA256之后的值
        reqData.put("accessCode", SecureUtil.sha256(accessCode));

        AccessToken adminTk = adminToken(IConst.SERVICE_HTTPDNS);
        if(adminTk == null) {
            return HandleResult.future(RetCode.INVALID_STATE, "invalid token");
        }

        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_HTTPDNS)
                .url("/company/register")
                .cid(this.id)
                .traceId(IConst.SERVICE_HTTPDNS + '_' + this.id)
                .body(JsonUtil.objToJson(reqData))
                .token(adminTk.generate());
        
        return ServiceClient.cloudPost(req).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to register", e);
                return;
            }

            if(hr.code == RetCode.OK) {
                LOG.debug("Success to registerToHttpdns for comany {}", this.id);
                save();
            } else {
                LOG.warn("Fail to registerToHttpdns for comany {},result:{}", this.id, hr.brief());
            }
        }, IThreadPool.Pool);
    }
//    
//    /**
//     * 注册所有公司到httpdns
//     * @return 异步执行结果
//     */
//    @SuppressWarnings("unchecked")
//    public static CompletableFuture<HandleResult> registerAllToHttpdns() {
//        CompletableFuture<HandleResult>[] tasks = new CompletableFuture[Companies.length];
//        int i = 0;
//        
//        for(CompanyInfo ci : Companies) {
//            tasks[i++] = ci.registerToHttpdns();
//        }
//        return CompletableFuture.allOf(tasks).thenApplyAsync(o -> {
//            for(CompletableFuture<HandleResult> t : tasks) {
//                try {
//                    HandleResult hr = t.get();
//                    if(hr.code != RetCode.OK) {
//                        return hr; //返回第一个不成功的结果
//                    }
//                } catch (InterruptedException | ExecutionException e1) {
//                    return HandleResult.InternalError;
//                }
//            }
//            return HandleResult.OK;
//        }, IThreadPool.Pool);
//    }

    /**
     * 将私网服务器的接入码信息存到httpdns服务中
     * @param insideIp 内网httpdns地址，不带端口
     * @param outsideIp 外网httpdns地址，不带端口
     * @param accessCode 网络接入码
     * @return 异步执行结果
     */
    public CompletableFuture<HandleResult> saveToHttpdns(
            String insideIp, String outsideIp, String accessCode) {
        if(StringUtil.isEmpty(insideAddr) || id <= IConst.LOCAL_COMPANY_ID) {
            return HandleResult.future(RetCode.INVALID_STATE, "company has not been set correctly");
        }

        //公有云httpdns不宜存明文接入码，以免引起猜疑，所以存储SHA256之后的值
        Map<String, Object> data = new HashMap<>();
        if(!StringUtil.isEmpty(accessCode)) {
            data.put("accessCode", SecureUtil.sha256(accessCode));
        }

        if(insideIp != null) { //除了自动获取，仍然可以手动设置
            data.put("insideAddr", insideAddr);
        }

        String outsideAddress;
        if(outsideIp != null) {//outsideAddr可以为长度为0的字符串，表示取消外网入口
            if(!outsideIp.isEmpty()) {
                String[] ss = outsideIp.split("[,; ]");//可以有多个，用逗号或分号分隔
                String addrs = "";
                for(String s : ss) {
                    if(IPUtil.isLanIp(s)) {
                        return HandleResult.future(RetCode.WRONG_PARAMETER, "invalid outsideAddr");
                    }
                    if(!addrs.isEmpty()) {
                        addrs += ',';
                    }
                    addrs += IPUtil.hostFormat(s, ChannelConfig.instance().httpPort);
                }
                outsideAddress = addrs;
            } else {
                outsideAddress = IConst.EMPTY_STR;
            }
            data.put("outsideAddr", outsideAddress);
        } else {
            outsideAddress = outsideAddr;
        }
        AccessToken adminTk = adminToken(IConst.SERVICE_HTTPDNS);
        if(adminTk == null) {
            return HandleResult.future(RetCode.INVALID_STATE, "invalid token");
        }

        HttpClient.ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_HTTPDNS)
                .url("/company/update")
                .cid(id)
                .traceId(IConst.SERVICE_HTTPDNS + '_' + id)
                .body(JsonUtil.objToJson(data))
                .token(adminTk.generate());
        
        return ServiceClient.cloudPut(req).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to register", e);
                return;
            }
            
            if(hr.code == RetCode.OK) {
                LOG.debug("Success to call saveToHttpdns");
                if(accessCode != null) {
                    this.accessCode = accessCode;
                }
                if(outsideAddress != null) {
                    setOutsideAddr(outsideAddress);
                }
                save();
            } else {
                LOG.warn("Fail to call saveToHttpdns,result:{}", hr.brief());
            }
        }, IThreadPool.Pool);
    }

    public AccessToken adminToken(String service) {
        return createAdminToken(tokenWorker,
                PartitionConfig.PRIVATE_CLOUD_PARTITION,
                id, IOAuth.ADMIN_UID, IOAuth.ADMIN_ACCOUNT,
                service, COMPANY_TOKEN_EXPIRES_IN);
    }
    
    public AccessToken adminToken(ITokenWorker tokenWorker, String service) {
        return createAdminToken(tokenWorker,
                PartitionConfig.PRIVATE_CLOUD_PARTITION,
                id, IOAuth.ADMIN_UID, IOAuth.ADMIN_ACCOUNT,
                service, COMPANY_TOKEN_EXPIRES_IN);
    }

    /**
     * 用于产生公司级的token，此类token用于请求公有云的接口，比如查询公司信息，下载证书等
     * 在公有云侧的company服务中验证，
     * 必须在已注册的情况下使用，否则服务侧的company中验证会失败，因为未注册时，服务侧没有公司的公钥
     * 
     * @param tw token生成器
     * @param partition 分区ID
     * @param cid 公司ID
     * @param caller 调用发起人帐号id
     * @param account 调用发起人帐号
     * @param service 被调服务
     * @param expiresIn 过期时长
     * 
     * @return token 返回token
     */
    private static AccessToken createAdminToken(ITokenWorker tw,
            int partition, int cid, String caller,
            String account, String service, long expiresIn) {
        if(tw == null) {
            LOG.error("TokenWorker is null,can't create admin token");
            return null;
        }
        long expiresAt = System.currentTimeMillis() + expiresIn;
        return tw.create(partition, caller, service, expiresAt,
                   account + AccessToken.EXT_SEPARATOR + cid, //ext
                   IOAuth.TOKENTYPE_USER);
    }

    public static AccessToken adminToken(int cid, ITokenWorker tokenWorker, String service) {
        return createAdminToken(tokenWorker,
                PartitionConfig.PRIVATE_CLOUD_PARTITION,
                cid, IOAuth.ADMIN_UID, IOAuth.ADMIN_ACCOUNT,
                service, COMPANY_TOKEN_EXPIRES_IN);
    }

    public boolean isValid() {
        return id > IConst.LOCAL_COMPANY_ID && !StringUtil.isEmpty(insideAddr);
    }

    private CompletableFuture<HandleResult> saveRootKeys(String token, String dataRootKp, String envRootKs) {
        ServiceReqBuilder putsIfAbs = ServiceClient.backendReqBuilder(IConst.SERVICE_KEYSTORE)
                .url("/keypair/putsIfAbsent") //如果存在，返回原有的
                .nodeId(id)
                .cid(id)
                .token(token)
                .traceId("save_root_kps_" + id)
                .put("kps", Arrays.asList(
                    Map.of("kp", dataRootKp, "type", DATAROOT_INPRIVATE),
                    Map.of("kp", envRootKs, "type", ENVROOT_INPRIVATE)));
        return ServiceClient.cloudPut(putsIfAbs);
    }
    
    private CompletableFuture<HandleResult> saveRootKey(String token, String key, String type) {
        ServiceReqBuilder putIfAbs = ServiceClient.backendReqBuilder(IConst.SERVICE_KEYSTORE)
                .url("/keypair/putIfAbsent") //如果存在，返回原有的
                .nodeId(id)
                .cid(id)
                .token(token)
                .traceId("save_root_kps_" + id)
                .put("kp", key)
                .put("type", type);
        return ServiceClient.cloudPut(putIfAbs);
    }
    
    /**
     * 查询根密钥对，包括数据根密钥对与环境根密钥对
     * @param token 管理员公司token
     * @return 根密钥对
     */
    public CompletableFuture<HandleResult> getRootKeys(String token, String[] types) {
        String url = "/keypair/gets?";
        for(String t : types) {
            if(url.indexOf('=') > 0) { //已经存在参数了
                url += '&';
            }
            url += "type=" + t; //都用相同的名字，服务端转成数组
        }
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_KEYSTORE)
                .url(url)
                .traceId("getcdr_" + id)
                .cid(id)
                .token(token);
        return ServiceClient.cloudGet(req);
    }
    
    public CompletableFuture<HandleResult> getRootKey(String token, String type) {
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_KEYSTORE)
                .url("/keypair/get?type=" + type)
                .traceId("getcdr_" + id)
                .cid(id)
                .token(token);
        return ServiceClient.cloudGet(req);
    }
    
    /**
     * 将公司登录密码从oldPwd换成newPwd
     * 除了更换公司登录密码外，还需要更改data_root_key的私钥密码
     * @param oldPwd 旧密码
     * @param newPwd 新密码
     */
    public CompletableFuture<HandleResult> changePwd(String oldPwd, String newPwd) {
        if(!Pattern.matches("[\\w|_!@#$%^&*]{4,20}", newPwd)) {
            return HandleResult.future(RetCode.WRONG_PARAMETER, "invalid new password");
        }

        AccessToken at = adminToken(IConst.SERVICE_KEYSTORE);
        if(at == null) {
            LOG.error("Fail to create access token for company {}", id);
            return HandleResult.future(RetCode.INTERNAL_ERROR);
        }

        String kpServiceToken = at.generate();
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
            .url("/company/changePwd")
            .traceId("changePwd_" + id)
            .cid(id)
            .put("id", id)
            .put("pwd", SecureUtil.sha256(oldPwd))
            .put("newPwd", SecureUtil.sha256(newPwd));
        return ServiceClient.cloudPost(req).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to changePwd, result:{}", hr.brief());
                return CompletableFuture.completedFuture(hr);
            }
            return getRootKeys(kpServiceToken, new String[]{DATAROOT_INPRIVATE, ENVROOT_INPRIVATE}).thenComposeAsync(r-> {
                if(r.code != RetCode.OK) {
                    LOG.error("Fail to restoreDataKeypair,result:{}", r.brief());
                    return HandleResult.future(r);
                }
                Map<String, Object> kps = ValParser.getAsObject(r.data, "list");
                EccKeyPair eccDataKp;
                String dataRootKp, envRootKs;
                
                try {
                    dataRootKp = ValParser.getAsStr(kps, DATAROOT_INPRIVATE);
                    eccDataKp = EccKeyPair.parse(dataRootKp, oldPwd);
                    dataRootKp = eccDataKp.toString(newPwd);

                    envRootKs = ValParser.getAsStr(kps, ENVROOT_INPRIVATE);
                    RootKeystore rks = RootKeystore.parse(envRootKs, oldPwd);
                    envRootKs = rks.encodeKeyStore(newPwd);
                } catch(Exception e) {
                    LOG.error("Fail to parse keypair", e);
                    return HandleResult.future(RetCode.DATA_WRONG, "invalid keypair from cloud");
                }
                return saveRootKeys(kpServiceToken, dataRootKp, envRootKs);
            });
        }, IThreadPool.Pool).exceptionally(e -> {
            LOG.error("Fail to changePwd", e);
            return HandleResult.InternalError;
        });
    }
    
    /**
     * @param pwd 公司登录密码
     */
    public CompletableFuture<HandleResult> changeAuth(String pwd) {
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
            .url("/company/changeAuthKey")
            .traceId("changeAuth_" + id)
            .cid(id)
            .put("id", id)
            .put("pwd", SecureUtil.sha256(pwd))
            .put("authKey", this.authKeyPair.toString(pwd)); //私钥加密后上传
        return ServiceClient.cloudPut(req).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to changeAuth, result:{}", hr.brief());
            }
            return hr;
        }, IThreadPool.Pool).exceptionally(e -> {
            LOG.error("Fail to changeAuth", e);
            return HandleResult.InternalError;
        });
    }

    /**
     * 从公有云上注销公司信息
     * @param creditCode 统一信用码
     * @param pwd 密码
     */
    public CompletableFuture<HandleResult> unRegister(String creditCode, String pwd) {
        if(!Pattern.matches("\\w{4,10}", pwd)) {
            return HandleResult.future(RetCode.WRONG_PARAMETER, "invalid password");
        }
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
                .cid(id)
                .url("/company/unregister")
                .traceId(creditCode)
                .body("{\"creditCode\":\"" + creditCode + "\",\"pwd\":\"" + pwd + "\"}");
        return ServiceClient.cloudPost(req);
    }

    /**
     * 更新公有云侧记录的公司信息
     * @param name 名称
     * @param country 国家
     * @param province 省
     * @param city 市
     * @param county 县/区
     * @param info 其他信息
     */
    public CompletableFuture<HandleResult> setInfo(String name,
            String country, String province, String city, String county,
            String info) {
        LOG.debug("setCompanyInfo(cid:{},{},{},{},{},{},{})",
                id, name, country, province, city, county, info);
        AccessToken at = adminToken(IConst.SERVICE_COMPANY);
        if (at == null) {
            return HandleResult.future(RetCode.INVALID_TOKEN, "fail to create company token");
        }

        ServiceReqBuilder builder = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
                .traceId(IConst.SERVICE_COMPANY + '_' + id)
                .url("/company/setinfo")
                .cid(id)
                .nodeId(id) //以公司id分片
                .token(at.generate());

        if(!StringUtil.isEmpty(name)) {
            builder.put("name", name);
            this.name = name;
        }

        if(country != null) {
            builder.put("country", country);
            this.country = country;
        }
        if(province != null) {
            builder.put("province", province);
            this.province = province;
        }

        if(city != null) {
            builder.put("city", city);
            this.city = city;
        }

        if(county != null) {
            builder.put("county", county);
            this.county = county;
        }

        if(info != null) {
            builder.put("info", info);
            this.info = info;
        }

        return ServiceClient.cloudPost(builder).thenApplyAsync(hr -> {
            if (hr.code != RetCode.OK) {
                LOG.error("Fail to setCompanyInfo, result:{}", hr.brief());
                return hr;
            }
            save();
            return hr;
        }, IThreadPool.Pool);
    }

    public String country() {
        return this.county;
    }

    public String province() {
        return this.province;
    }

    public String city() {
        return this.city;
    }

    public String county(){
        return this.county;
    }

    public String name(){
        return this.name;
    }

    public String info() {
        return this.info;
    }

    @Override
    public String toString() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ITEM_COMPANYID, id);
        cfg.put(ITEM_NAME, name);
        cfg.put(ITEM_COUNTRY, country);
        cfg.put(ITEM_PROVINCE, province);
        cfg.put(ITEM_CITY, city);
        cfg.put(ITEM_COUNTY, county);
        cfg.put(ITEM_INFO, info);
        cfg.put(ITEM_INSIDEADDR, insideAddr);
        cfg.put(ITEM_OUTSIDEADDR, outsideAddr);
        cfg.put(ITEM_CREDITCODE, creditCode);
        cfg.put(ITEM_AREA, area);
        cfg.put(ITEM_DBNO, dbNo);

        secureSave(cfg, ITEM_DNSACCESSCODE, accessCode);
        saveKeyPair(cfg, ITEM_AUTHKEY, authKeyPair);
        
        return JsonUtil.objToPrettyJson(cfg);
    }
    
    /**
     * 将内存中的公司信息存入preference中
     */
    public static synchronized void save() {
        if(cfgFile == null) {
            LOG.warn("No config file, can't save it");
            return;
        }
        FileUtil.writeFile(cfgFile, instance.toString(), IConst.DEFAULT_CHARSET);
    }
    
//    public static String toText() {
//        if(Companies == null) {
//            return "[]";
//        }
//        String s = "";
//        for(CompanyInfo ci : Companies) {
//            if(!s.isEmpty()) {
//                s += ",\n";
//            }
//            s += ci.toString();
//        }
//        return "[\n" + s + "\n]";
//    }

    /**
     * 向至简网格验证公司密码是否正确
     * @param pwd 经过sha256运算后的公司密码
     */
    public CompletableFuture<HandleResult> verifyPwd(String pwd) {
        AccessToken adminToken = adminToken(IConst.SERVICE_COMPANY);
        if(adminToken == null) {
            LOG.error("Fail to create adminToken");
            return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to create admin token");
        }

        ServiceReqBuilder reqBuilder = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
                .cid(id)
                .nodeId(id)
                .token(adminToken.generate())
                .url("/company/verifypwd")
                .appendPara("pwd", pwd)
                .traceId(IConst.SERVICE_COMPANY + '_' + id);
        return ServiceClient.cloudGet(reqBuilder);
    }

    /**
     * 在根环境调用，创建运行在根环境的公司信息
     * @param cid 公司id
     * @param name 名称
     * @param creditCode 接入码
     * @param country 国家
     * @param province 省份
     * @param city 城市
     * @param county 县
     * @param info 描述
     * @param area 地区编码
     * @param dbNo 数据库编码
     * @return 公司信息
     */
    public static CompanyInfo createRoot(int cid, String name, String creditCode,
            String country, String province, String city, String county, String info,
            int area, int dbNo) {
        CompanyInfo ci = new CompanyInfo(cid, RunMode.ROOT, name, creditCode,
                country, province, city, county, info,
                PartitionConfig.instance().cloudHttpdns[0]); //在根环境，所以外网接入地址一定是cloudHttpDns
        ci.area= area;
        ci.dbNo = dbNo;
        return ci;
    }
    
    private static void secureSave(Map<String, Object> cfg, String key, String value) {
//        String cipher;
//        try {
//            byte[] ciphered = AbsRootKey.encode(value.getBytes(IConst.DEFAULT_CHARSET));
//            cipher = "1" + ByteUtil.bin2base64(ciphered);
//        } catch(Exception e) {
//            LOG.error("Fail to save {}", key, e);
//            cipher = "0" + value;
//        }
//        cfg.put(key, cipher);
        cfg.put(key, value);
    }

    private static String secureRead(Map<String, Object> cfg, String key, String defaultValue) {
        String s = ValParser.getAsStr(cfg, key);
//        if(s.startsWith("0")) { //加密失败的情况，直接存明文，以0开头
//            return s.substring(1);
//        }
//
//        try {
//            byte[] ciphered = ByteUtil.base642bin(s.substring(1));
//            byte[] plain = AbsRootKey.decode(ciphered);
//            return new String(plain, IConst.DEFAULT_CHARSET);
//        } catch (Exception e) {
//            LOG.warn("Fail to decode perference `{}`, return defaultValue", key, e);
//        }
//        return defaultValue;
        return StringUtil.isEmpty(s) ? defaultValue : s;
    }

    /**
     * 从配置文件中读取密钥对
     * @param cfg 配置map
     * @param name 密钥对名称
     * @return 密钥对
     * @throws MeshException 异常
     */
    private static EccKeyPair readKeyPair(Map<String, Object> cfg, String name) throws MeshException {
        String sKp = secureRead(cfg, name, IConst.EMPTY_STR);
        if(StringUtil.isEmpty(sKp)) {
            LOG.error("Fail to readKeyPair({}),not exists", name);
            return null;
        }

        try {
            EccKeyPair kp = EccKeyPair.parse(sKp);
            LOG.debug("EccKeyPair:{},publicKey:{}", name, kp.publicKey2Str());
            return kp;
        } catch (Exception e) {
            throw new MeshException("fail to parse keypair " + name, e);
        }
    }

    private static void saveKeyPair(Map<String, Object> cfg, String name, EccKeyPair kp) {
        secureSave(cfg, name, kp.toString());
    }
}
