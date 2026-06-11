package cn.net.zhijian.mesh.frm.config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.bean.RequestStat;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.dbworker.SearchDBWorker;
import cn.net.zhijian.mesh.dbworker.SqliteWorker;
import cn.net.zhijian.mesh.dbworker.TreeDBWorker;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsSearchDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IServiceWatcher;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.frm.method.ApiMethod;
import cn.net.zhijian.mesh.frm.tokenworker.EccTokenWorker;
import cn.net.zhijian.mesh.prot.http.NullServerRequest;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.FifoCache;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 每个服务的目录下都有一个service.cfg配置文件，
 * 里面记录了服务版本、作者、公私钥、客户端等信息，在服务发布时既可以存入版本包。
 * 也可以承载一些应用特有的信息，需要应用启动时自行处理，但是需要保证可以写入发布版本中。
 * @author flyinmind of csdn.net
 */
public final class ServiceInfo {
    private static final Logger LOG = LogUtil.getInstance();

    private static final String DEFAULT_VER = "0.1.0";
    private static final String INTRODUCTION_NAME = "introduction.json";
    private static final Pattern NAME_CHECKER = Pattern.compile("^\\w{1,30}$");
    private static final String VERSION_CHECKER = "^(\\d{1,3}\\.){2}\\d{1,3}$";
    private static final FifoCache<String, Integer> DbNos = new FifoCache<>(1000);
    /**
     * 如果服务有自己的java，需要打包到一个jar文件中，放在服务的根目录，
     * 框架在加载api时，如果handler不为空则会首先尝试从classpath中加载类，
     * 如果类不存在，再从此jar中加载
     */
    private static final String SERVICE_LIBS_PATH = "libs";

    private static final String CFG_AUTHOR = "author";
    public static final String CFG_VER = "version";
    private static final String CFG_MINCOMPATIBLE = "minCompatible";
    private static final String CFG_DISPLAYNAME = "displayName";
    private static final String CFG_DEPENDENCIES = "dependencies";
    private static final String CFG_LEVEL = "level";

    /*
     * 服务类型
     * COMPANY:区分公司；
     * COMMON:通用的，不区分公司；
     * CLOUD:云侧服务，不会在私有云安装，不区分公司；
     * PERSONAL:个人服务，不会在私有云安装。
     */
    private static final String CFG_TYPE = "type";

    private static final String CFG_NAME = "name";
    private static final String CFG_MINVER = "minVersion";
    private static final String CFG_FEATURES = "features";
    private static final String CFG_CLIENT = "client";
    
    //服务初始化类，用在<code>VirtualServer</code>中
    private static final String CFG_WATCHER = "watcher";
    //-------------------------------------------------------------------------
    /* 私有云可以安装COMPANY/COMMON服务，
     * 某个公司的服务要么全部安装在私有云，要么全部安装在公有云。
     * CLOUD服务只运行在公有云中，提供公共能力；
     * PERSONAL是针对个人的服务，帐号系统与公司服务区分开来，所以在客户端会存在两个帐号登录，
     * 一个是个人的，一个是公司的，两个帐号用在不同的服务中。
     */
    public enum ServiceType {
        COMPANY,/* 0:数据库名中携带cid，使用USER认证*/
        COMMON,/* 1:数据库名不携带cid，也就是说数据不区分是哪家公司的，比如config、seqid */
        CLOUD, /* 2:服务在公有云中提供公共能力，数据不区分公司，比如HTTPDNS、AI之类，可以使用COMPANY、UNIUSER认证 */
        PERSONAL, /* 3:针对个人的服务，数据在云端，数据按用户ID区分，使用UNIUSER认证 */
        BUILTIN; //内置，只有backend
        
        public static ServiceType parse(String type) {
            String n = type.toUpperCase();
            if(n.equals(COMMON.name())) {
                return COMMON;
            }
            if(n.equals(CLOUD.name())) {
                return CLOUD;
            }
            if(n.equals(PERSONAL.name())) {
                return PERSONAL; //私人服务，比如记事本、密码本等服务
            }
            if(n.equals(BUILTIN.name())) {
                return BUILTIN;
            }
            return COMPANY; //不配置，则表示区分公司
        }
    }

    public enum ClientType { //端侧类型
        NONE,/*无任何端侧的服务*/
        COMPONENT,/*提供端侧组件*/
        CLIENTUI /*有完整的端侧UI*/
    }
    //-------------------------------------------------------------------------
    //本地数据库工人，特别是bios中，有特殊处理，所以不放在webdb中
    //初始化是在服务本身的watcher.beforeLoad中，销毁在watcher.destroy中
    private static final Map<String, AbsDBWorker> locDBWorkers = new ConcurrentHashMap<>();

    public final String name; //内部名称，全局唯一
    public final String displayName; //显示名称
    public final String author; //公司或个人，或者公司+部门等，格式无明确要求
    public final int version; //版本，9位十进制数，Major.Minor.Patch各3位
    public final int minCompatible; //最小可兼容版本，如果不能包括老版本的所有接口，则不能兼容
    public final Dependency[] dependencies; //依赖的服务
    public final ServiceType type;

    /**
     * 应用访问oAuth生成token时，使用自己的私钥签名，在oAuth中使用对应的公钥验签，
     * 延签通过后，oAuht才会继续生成token
     */
    public final EccTokenWorker tokenWorker;
    /**
     * classLoader是每个服务独有的，
     * 当多个服务运行在同一个实例进程时，导致服务可以使用其他服务的自定义逻辑。
     * 本系统的设计，容忍这种缺陷。
     * 在服务设计时绝对不能依赖这种缺陷，因为服务的部署位置是非常随机的。
     */
    public final ClassLoader classLoader;
    public final String homeDir;

    /**
     * 在VirtualServer中的不同阶段调用watcher的接口，
     * 比如安装、卸载，加载完毕、销毁，以及定时将状态上报给bios
     */
    public final IServiceWatcher watcher;
    
    /**
     * 访问统计，每个公司一个
     */
    private final Map<Integer, RequestStat> stats = new HashMap<>();

    /**
     * 服务等级与安全等级，
     * 0-8，服务等级，默认为255级：
     * 0：底层服务，比如bios、webdb；
     * 1：基础服务，不涉及最终用户的服务，比如oauth2、assets、seqid、verifycode、boot等；
     * 2：公共底层服务，比如user/uniuser、schedule、workflow等服务；
     * 3：公共服务，与用户有关，但是功能可共用，比如score；
     * >=4：业务服务，基于user等服务，提供一定的功能，比如crm、member等
     * 8-16，安全等级：
     * 0：普通
     * 1：保密，需要禁止截屏
     * 2：绝密，未定义
     *
     */
    public final int level;
    public final ClientType clientType;
    public final boolean hasDb; //是否有数据库
    public final boolean hasApi; //是否定义了接口

    /**
     * 用于记录接口处理的次数、处理时长，
     * 每隔一段时间上报给bios服务，维持活跃状态。
     */
    public long cpuTime = 0;
    //客户端路由中是否可见，有USER类接口，或有界面，则必须可见，如果不可见，客户端得不到它的访问地址
    public boolean visible;
    
    //初始化相关的接口，不会提供给外部调用，只能在om.service.InitDb中调用
    private List<ApiMethod> initApis = null;
    
    /**
     * 坏味道的构造函数，不过是私有的
     * @param homeDir 服务的根目录
     * @param name 服务名称
     * @param displayName 显示名称
     * @param author 作者
     * @param version 版本
     * @param dependencies 依赖的服务列表
     * @param level 安全等级+服务等级
     * @param clientType 端侧类型
     * @param tokenWorker token检查器
     * @param classLoader 加载器
     * @param type 服务类型 COMPANY/COMMON/CLOUD/PERSONAL
     * @param hasApi 是否有api
     * @param hasDb 是否有db定义
     */
    private ServiceInfo(String homeDir, String name, String displayName, String author, 
            String version, String minCompatible, Dependency[] dependencies, int level, ClientType clientType,
            EccTokenWorker tokenWorker, ClassLoader classLoader,
            ServiceType type, IServiceWatcher watcher, boolean hasApi, boolean hasDb) {
        this.homeDir = homeDir;
        this.name = name;
        this.displayName = displayName;
        this.author = author;
        this.version = StringUtil.verToInt(version);
        this.minCompatible = StringUtil.verToInt(minCompatible);
        this.dependencies = dependencies;
        this.level = level;
        this.clientType = clientType;
        this.type = type;
        this.tokenWorker = tokenWorker;
        this.visible = clientType != ClientType.NONE;

        this.classLoader = classLoader;
        this.watcher = watcher != null ? watcher : IServiceWatcher.DefaultWatcher;
        this.hasApi = hasApi;
        this.hasDb = hasDb;
    }

    private ServiceInfo(String homeDir, String name, String displayName, String author,
                        String version, String minCompatible, Dependency[] dependencies, int level, ClientType clientType,
                        EccTokenWorker tokenWorker, ClassLoader classLoader,
                        ServiceType type, IServiceWatcher watcher) {
        this(homeDir, name, displayName, author, version, minCompatible, dependencies,
             level, clientType, tokenWorker, classLoader, type, watcher,
            new File(FileUtil.addPath(homeDir, IConst.SERVICE_URL_API)).exists(), //存在api目录，则认为有接口，不判断是否实际有接口配置文件
            //new File(FileUtil.addPath(homeDir, IConst.LOCAL_DATABASE_CONFIG_FILE)).exists()
            //本地数据库在服务的watcher.beforeLoad中加载初始化
            //所以本地数据库不当作数据库处理，类似本地文件
            new File(FileUtil.addPath(homeDir, IConst.DATABASE_CONFIG_FILE)).exists());
    }
    /**
     * 用于产生运维平台接口的serviceInfo，这类service无需上报给bios，不区分公司
     * @param homeDir 服务根目录
     * @param name 名称
     */
    public ServiceInfo(String homeDir, String name) {
        this(homeDir, name, name, "flyinmind@zhijian.net.cn", DEFAULT_VER, DEFAULT_VER,
             null, 0, ClientType.NONE, null,
             Thread.currentThread().getContextClassLoader(), ServiceType.COMMON, null);
    }

    /**
     * 用于产生运维平台接口的serviceInfo，这类service无需上报给bios，不区分公司
     * @param homeDir 服务根目录
     * @param name 名称
     * @param tokenWorker token验证器
     * @param dependencies 依赖服务列表
     * @param watcher 服务监视器
     */
    public static ServiceInfo createBuiltIn(String homeDir, String name, EccTokenWorker tokenWorker,
            Dependency[] dependencies, IServiceWatcher watcher) {
        return new ServiceInfo(homeDir, name, name, "flyinmind@zhijian.net.cn", DEFAULT_VER, DEFAULT_VER,
             dependencies, 0, ClientType.NONE, tokenWorker,
             Thread.currentThread().getContextClassLoader(),
             ServiceType.BUILTIN, watcher,
             true, //builtin的服务，一定提供了接口，都是以原生实现方式提供，否则没有存在的意义
             false);
    }

    /**
     * 
     * @param homeDir 服务根目录，包括服务名称
     * @param serviceName 服务名称
     * @param cfg 配置内容
     * @param ekp 记录密钥对，会存到文件中，重启时复用上一次的
     * @param completeLoad 是否完整加载，不完整加载用在JsServer安装服务时
     * @return 服务信息
     */
    @SuppressWarnings({"unchecked"}) //cl在VirtualServe.destroy中close
    public static ServiceInfo parse(String homeDir, String serviceName, Map<String, Object> cfg,
            EccKeyPair ekp, boolean completeLoad) {
        String author = ValParser.getAsStr(cfg, CFG_AUTHOR);
        if(StringUtil.isEmpty(author)) {
            LOG.error("Invalid author of service {}", serviceName);
            return null;
        }

        String version = ValParser.getAsStr(cfg, CFG_VER);
        if(StringUtil.isEmpty(version) || !version.matches(VERSION_CHECKER)) {
            LOG.error("Invalid version {}", version);
            return null;
        }

        String displayName = ValParser.getAsStr(cfg, CFG_DISPLAYNAME);
        if(StringUtil.isEmpty(displayName)) {
            LOG.error("Invalid displayName {}", displayName);
            return null;
        }
        
        Dependency[] dependencies;
        List<Object> dpds = ValParser.getAsList(cfg, CFG_DEPENDENCIES);
        if(dpds != null && !dpds.isEmpty()) {
            dependencies = new Dependency[dpds.size()];
            for(int i = 0; i < dpds.size(); i++) {
                Map<String, Object> one = ValParser.getAsObject(dpds, i);
                if(one == null || one.isEmpty()) {
                    LOG.error("Invalid dependency config, it must be a map, in service {}", serviceName);
                    return null; //直接粗暴地结束解析
                }
                Dependency dpd = Dependency.parse(one);
                if(dpd == null) {
                    LOG.error("Invalid dependency config {} in service {}", one, serviceName);
                    return null;
                }
                dependencies[i] = dpd;
            }
        } else {
            dependencies = new Dependency[0];
        }
        int level = ValParser.getAsInt(cfg, CFG_LEVEL, 10000);
        String sType = ValParser.getAsStr(cfg, CFG_TYPE);
        ServiceType type = ServiceType.parse(sType);

        ClientType clientType;
        String fileDir = FileUtil.addPath(homeDir, IConst.SERVICE_FILE_DIR);
        if(new File(FileUtil.addPath(fileDir, IConst.INDEX_FILE)).exists()) {
            //有index.html表示在端侧是可以显示的
            clientType = ClientType.CLIENTUI;
        } else {
            File fileDirF = new File(fileDir);
            FilenameFilter ff = (dir, name) -> name.toLowerCase().endsWith(".js");
            clientType = ClientType.NONE;
            if(fileDirF.exists()) {
                String[] files = fileDirF.list(ff);
                if(files != null && files.length > 0) {
                    //无index.html，但是有file目录，则表示存在component
                    clientType = ClientType.COMPONENT;
                }
            }
        }
        
        EccTokenWorker tokenWorker;
        int signType = IConst.SERVICE_OM.equals(serviceName) ? IOAuth.SIGNTYPE_OMKEY : IOAuth.SIGNTYPE_APPKEY;
        if(ekp == null) {
            tokenWorker = EccTokenWorker.nullTokenWorker(signType);
        } else {
            tokenWorker = new EccTokenWorker(ekp, signType);
            if(tokenWorker.isEmpty()) {
                LOG.error("Invalid service key of {}", serviceName);
                return null;
            }
        }
        
        String minCompatible = ValParser.getAsStr(cfg, CFG_MINCOMPATIBLE, DEFAULT_VER);
        if(!completeLoad) { //不完全加载时，只加载基本配置信息
            return new ServiceInfo(homeDir, serviceName, displayName, author, version, minCompatible,
                    dependencies, level, clientType, tokenWorker, null, type, null);
        }
        ClassLoader mainCl = Thread.currentThread().getContextClassLoader();
        ClassLoader cl = mainCl; //默认使用进程的classloader
        File libsPath = new File(FileUtil.addPath(homeDir, SERVICE_LIBS_PATH));

        /*
         * 只有libs文件夹存在，则为此目录建一个独立的classloader，加载其中的所有jar。
         * 此classloader可以使用主classloader中所有class，但是不能使用其他service中加载的class。
         */
        if(libsPath.exists()) {
            List<File> jarFiles = FileUtil.listFiles(libsPath, "^.+\\.jar$");
            URL[] jars = new URL[jarFiles.size()];
            int i = 0;

            for(File jarFile : jarFiles) {
                try {
                    URL jarUrl = jarFile.toURI().toURL();
                    jars[i++] = jarUrl;
                    LOG.debug("{}:{}", serviceName, jarUrl);
                } catch (MalformedURLException e) {
                    LOG.error("Fail to create class loader from {}", libsPath, e);
                }
            }

            cl = new URLClassLoader(jars, mainCl);            
        }

        String watcherName = ValParser.getAsStr(cfg, CFG_WATCHER, null);
        IServiceWatcher watcher;
        if(StringUtil.isEmpty(watcherName)) {
            watcher = IServiceWatcher.DefaultWatcher;
        } else {
            try {
                Class<?> cls = cl.loadClass(watcherName);
                if(cls == null || !IServiceWatcher.class.isAssignableFrom(cls)) {
                    LOG.error("Fail to load watcher {} of service `{}`", watcherName, serviceName);
                    return null;
                }
                watcher = ((Class<? extends IServiceWatcher>)cls).getConstructor().newInstance();
            } catch (Exception e) {
                LOG.error("Fail to load watcher {} of service `{}`", watcherName, serviceName, e);
                return null;
            }
        }

        return new ServiceInfo(homeDir, serviceName, displayName, author, version, minCompatible,
                dependencies, level, clientType, tokenWorker, cl, type, watcher);
    }
    
    public static ServiceInfo parse(String serviceHome, String serviceName, EccKeyPair ekp, boolean completeLoad) {
        String serviceCfgFile = FileUtil.addPath(serviceHome, IConst.SERVICE_CONFIG_FILE);
        File serviceCfgF = new File(serviceCfgFile);
        if(!serviceCfgF.exists()) {
            LOG.warn("No {} under service {}, ignore it", IConst.SERVICE_CONFIG_FILE, serviceName);
            return null;
        }

        Map<String, Object> cfg = JsonUtil.jsonFileToMap(serviceCfgF, false);
        if (cfg == null || cfg.isEmpty()) {
            LOG.error("Service config file is empty, {}", serviceCfgFile);
            return null;
        }
        return parse(serviceHome, serviceName, cfg, ekp, completeLoad);
    }

    /**
     * 作为请求参数，转成map，防止proguard改变了字段名称，导致参数错误
     * @return maps
     */
    public List<Map<String, Object>> getDpdMaps() {
        List<Map<String, Object>> dpds = new ArrayList<>();
        if(dependencies == null) {
            return dpds;
        }
        for(Dependency d : dependencies) {
            Map<String, Object> map = new HashMap<>();
            map.put(CFG_NAME, d.name);
            map.put(CFG_MINVER, d.minVersion);
            map.put(CFG_FEATURES, d.features);
            dpds.add(map);
        }
        return dpds;
    }
    
    public String version() {
        return StringUtil.intToVer(version);
    }
    
    @Override
    public String toString() {
        return name;
    }

    /**
     * 依赖服务的信息
     * @author flyinmind of csdn.net
     *
     */
    public static class Dependency {
        public final String name;
        public final int minVersion;
        public final String features; //需要使用的features，以逗号分隔

        // 是否同时依赖端侧，
        // 如果依赖，则端侧下载时，同时下载依赖服务的端侧代码
        public final boolean client;

        public Dependency(String name, String minVersion, String features, boolean client) {
            this.name = name;
            this.minVersion = StringUtil.verToInt(minVersion);
            this.features = features;
            this.client = client;
        }

        public static Dependency parse(Map<String, Object> cfg) {
            String name = ValParser.getAsStr(cfg, CFG_NAME);
            if(StringUtil.isEmpty(name) || !NAME_CHECKER.matcher(name).matches()) {
                LOG.error("Invalid dependency name {}", name);
                return null;
            }

            String minVersion = ValParser.getAsStr(cfg, CFG_MINVER);
            if(StringUtil.isEmpty(minVersion) || !minVersion.matches(VERSION_CHECKER)) {
                LOG.error("Invalid minVersion {}", minVersion);
                return null;
            }
            
            String features = ValParser.getAsStr(cfg, CFG_FEATURES);
            boolean client = ValParser.getAsBool(cfg, CFG_CLIENT, false);

            return new Dependency(name, minVersion, features, client);
        }
    }

    private String toAppString() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"").append(CFG_AUTHOR).append("\":\"").append(this.author)
          .append("\",\"").append(CFG_VER).append("\":\"").append(StringUtil.intToVer(this.version))
          .append("\",\"").append(CFG_NAME).append("\":\"").append(this.name)
          .append("\",\"").append(CFG_DISPLAYNAME).append("\":\"").append(this.displayName)
          .append("\",\"").append(CFG_LEVEL).append("\":").append(this.level)
          .append(",\"").append(CFG_TYPE).append("\":\"").append(this.type.name()).append('"');
        if(this.dependencies != null && this.dependencies.length > 0) {
            sb.append(",\"").append(CFG_DEPENDENCIES).append("\":[");
            int n = 0;
            for(Dependency d : this.dependencies) {
                if(d.client) {
                    if(n > 0) {
                        sb.append(',');
                    }
                    sb.append('"').append(d.name).append('"');
                    n++;
                }
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * 压缩服务目录下file子目录中的所有文件，并添加app.cfg文件，
     * 只要有文件比当前zip包时间更晚，则会重新生成。
     * 此文件是提供给客户端的，是客户端的UI。
     * @param excludes 不压缩的文件列表
     * @param zipFile 压缩的文件
     * @return 压缩的文件个数，0表示无文件，-1表示错误，>0表示压缩成功
     */
    public int compressClient(Set<String> excludes, File zipFile) {
        String srcRoot = FileUtil.addPath(homeDir, IConst.SERVICE_FILE_DIR) + File.separatorChar;
        File rootF = new File(srcRoot);
        File[] files = rootF.listFiles();
        if(files == null || files.length == 0) {
            return 0;
        }

        int n = 0;
        String name;
        try(ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for(File f : files) {
                name = f.getName();
                if(name.charAt(0) == '_') {
                    continue; //根目录下，下划线开头的文件或文件夹不打入压缩包
                }
                if(excludes != null && excludes.contains(name)) {
                    continue;
                }

                if(f.isFile()) {
                    FileUtil.zipOneFile(srcRoot, f, zos);
                    n++;
                    continue;
                }
                n += FileUtil.zipOneDir(srcRoot, f, zos, excludes);
            }
            String s = toAppString();

            FileUtil.zipContent("app.cfg", s.getBytes(IConst.DEFAULT_CHARSET), zos);
            return n;
        } catch (IOException e) {
            LOG.error("Fail to zip files under {} to {}", srcRoot, zipFile, e);
            return -1;
        }
    }
    
    /**
     * 下载介绍，不影响业务使用，所以可以失败
     * @param serviceHome 服务根目录
     * @param verRoot 服务端版本根目录
     * @param service 服务名称
     * @return 异步结果
     */
    private static CompletableFuture<HandleResult> getIntroduction(String serviceHome, String verRoot, String service) {
        String fileRoot = FileUtil.addPath(serviceHome, IConst.SERVICE_FILE_DIR);
        String introSaveAs = FileUtil.addPath(serviceHome, IConst.SERVICE_URL_API, INTRODUCTION_NAME);
        String introUrl = verRoot + '/' + INTRODUCTION_NAME;
        return HttpClient.download(introUrl, null, introSaveAs).whenCompleteAsync((hr, e) -> { //下载introduction.json
            if(e != null) {
                LOG.warn("Fail to down load introduction", e);
                return;
            }

            if(hr.code != RetCode.OK) {
                LOG.debug("Fail to download {}.{} from {},result:{}", service, INTRODUCTION_NAME, introUrl, hr.brief());
                return;
            }

            File file = new File(introSaveAs);
            if(file.length() == 0) {
                LOG.warn("{}.{} is empty from {}", service, INTRODUCTION_NAME, introUrl);
                return;
            }
            Map<String, Object> intro = JsonUtil.jsonFileToMap(file, true);
            Map<String, Object> introduce;
            if(intro == null || (introduce = ValParser.getAsObject(intro,"introduce")) == null) {
                LOG.error("`{}.{}` is invalid", service, INTRODUCTION_NAME);
                return;
            }

            List<Object> imgs = ValParser.getAsList(introduce, "images");
            if(imgs == null || imgs.isEmpty()) {
                return;
            }

            for(Object o : imgs) {
                Map<String, Object> img = ValParser.parseObject(o);
                String src = ValParser.getAsStr(img, "src");
                String saveAs = FileUtil.addPath(fileRoot, src);
                String imgUrl = src.charAt(0) == '/' ? verRoot + src : verRoot + '/' + src;
                HttpClient.download(imgUrl, null, saveAs).whenCompleteAsync((imgHr, ex)->{
                    if(imgHr.code != RetCode.OK || ex != null || !FileUtil.isExist(saveAs)) {
                        HttpClient.download(imgUrl, null, saveAs); //只重试一次
                    }
                }, IThreadPool.Pool); //下载图片，不必阻塞，失败了也不影响业务功能
            }
        }, IThreadPool.Pool);
    }

    public static CompletableFuture<HandleResult> getIntroduction(ServiceInfo si, int cid, int area) {
        if(si.type == ServiceType.CLOUD
           || si.type == ServiceType.PERSONAL
           || si.type == ServiceType.BUILTIN) { //云侧、个人、内置服务无需下载介绍
            return HandleResult.future();
        }
        String intro = FileUtil.addPath(si.homeDir, IConst.SERVICE_URL_API, INTRODUCTION_NAME);
        if(FileUtil.isExist(intro)) { //存在introduction.json，则表示已下载过
            return HandleResult.future();
        }
        LOG.debug("getIntroduction of {}", si.name);
        return ServiceClient.getCdnServer(si.name, area, cid).thenComposeAsync(srv -> {
            if (srv == null) {
                return HandleResult.future(RetCode.INVALID_NODE, "no cdn");
            }
            String verUrl = UrlPathInfo.append(srv, new String[]{si.name, StringUtil.intToVer(si.version)});
            return getIntroduction(si.homeDir, verUrl, si.name);
        }, IThreadPool.Pool);
    }
    
    /**
     * 设置本地数据库工人。
     * 本地数据库不在webdb的掌控中，默认不同步，也不会随着它关闭而关闭
     * 不是每个服务都有本地数据库，且此函数只在各个服务的Watcher中调用，
     * 不存在同步互斥的问题，所以无需synchronized
     * @param dw 数据库工人，可以是rdb、treedb、searchdb
     * @param coreDb 核心rdb数据库，treedb、searchdb都是基于rdb实现的
     */
    public void addLocalDBWorker(AbsDBWorker dw, AbsRDBWorker coreDb) {
        String id = AbsDBWorker.dbId(IConst.LOCAL_COMPANY_ID, name, dw.type() + '_' + dw.name());
        locDBWorkers.put(id, dw);
        if(coreDb != null) {
            //本地数据库默认不同步，但是在bios中需要同步，所以同时记录核心的rdb信息
            id = AbsDBWorker.dbId(IConst.LOCAL_COMPANY_ID, name, coreDb.type() + '_' + coreDb.name());
            if(!locDBWorkers.containsKey(id)) {
                locDBWorkers.put(id, coreDb);
            }
        }
    }
    
    public AbsDBWorker getLocalDBWorker(String type, String db) {
        if(locDBWorkers == null) {
            return null;
        }
        String id = AbsDBWorker.dbId(IConst.LOCAL_COMPANY_ID, name, type + '_' + db);
        return locDBWorkers.get(id);
    }
    
    public void addInitApi(ApiMethod init) {
        if(initApis == null) {
            initApis = new ArrayList<>();
        }
        initApis.add(init);
    }
    
    public List<ApiMethod> getInitApis() {
        return initApis;
    }
    
    public static void destroyLocalDBWorkers() {
        if(locDBWorkers.isEmpty()) {
            return;
        }

        LOG.debug("destroyLocalDBWorkers");
        locDBWorkers.forEach((k, dw)-> {
            try {
                dw.close();
            } catch (IOException e) {
                LOG.error("Fail to close db worker(name:{},type:{})", dw.name(), dw.type(), e);
            }
        });
        locDBWorkers.clear();
    }
    
    public RequestStat getStat(int cid) {
        return stats.get(cid);
    }
    
    /**
     * 增加调用统计，因为只有第一次get不到时才会调用它，
     * 所以此处使用了同步，而不是使用ConcurrentHashMap
     * 虽然可能会导致多个现成并发加入，只要有了同步，不会同时加入，以最后一个为准
     * 统计时有一点点出入，没有什么问题
     * @param cid 公司id
     * @param rs 统计信息类
     */
    public synchronized void addStat(int cid, RequestStat rs) {
        stats.put(cid, rs);
    }
    
    public Map<Integer, RequestStat> stats() {
        return stats;
    }
    
    public String createToken(String callee, long expiresAt) {
        AccessToken at = tokenWorker.create(PartitionConfig.instance().partition,
                name, callee, expiresAt, "*", AccessToken.TOKENTYPE_SERVICE);
        if(at == null) {
            LOG.error("Fail to create access token for {}.{},expiresAt:{}", name, callee, expiresAt);
            return null;
        }
        return at.generate();
    }
    
    public String createToken(int cid, String callee) {
        AccessToken at = tokenWorker.create(PartitionConfig.instance().partition,
                name, callee, AccessToken.EXT_FEATURE_ALL + cid, AccessToken.TOKENTYPE_SERVICE);
        if(at == null) {
            LOG.error("Fail to create access token for {}.{}", name, callee);
            return null;
        }
        return at.generate();
    }
    //-------------------------------------------------------------------------
    private static String[] ServicesDirs;

    /**
     * 初始化服务根目录，必须在启动前就设置
     * 比如放在main中，或者Android的Application中
     * 将工作目录下所有以services开头的目录全部算进去
     * 如果目录是空的，则只有一个services目录，但是它其实不存在
     * @param workDir 工作目录
     */
    public static void setWorkDir(String workDir) {
        File root = new File(workDir);
        File[] ff = root.listFiles();
        if(ff == null || ff.length == 0) {
            ServicesDirs = new String[] {FileUtil.addPath(workDir, IConst.SERVICES_ROOT)};
            return;
        }
        
        List<String> list = new ArrayList<>();
        for(File f : ff) {
            if(!f.isDirectory()) {
                continue;
            }
            String name = f.getName();
            if(name.startsWith(IConst.SERVICES_ROOT)) {
                list.add(FileUtil.addPath(workDir, name));
            }
        }
        if(list.isEmpty()) {
            ServicesDirs = new String[] {FileUtil.addPath(workDir, IConst.SERVICES_ROOT)};
        } else {
            ServicesDirs = list.toArray(new String[] {});
        }
    }

    public static String servicesRoot() {
        return ServicesDirs[0]; //默认只返回第一个默认目录services
    }
    
    /**
     * 列举所有以services开头的目录下的子文件夹，子文件夹下面必须有service.cfg文件
     * @return 服务列表
     */
    public static List<String> listServices() {
        List<String> ff = new ArrayList<>();
        if(ServicesDirs == null) {
            return ff;
        }
        for(String s : ServicesDirs) {
            File serviceRoot = new File(s);
            if(!serviceRoot.exists()) {
                continue;
            }
            File[] fl = serviceRoot.listFiles();
            if(fl == null) {
                continue;
            }
            for(File f : fl) {
                if (!f.isDirectory()) {
                    continue;
                }

                String name = f.getName();
                File sif = new File(FileUtil.addPath(s, name, IConst.SERVICE_CONFIG_FILE));
                if(!sif.exists() || sif.length() == 0) {
                    continue;
                }
                
                if(NAME_CHECKER.matcher(name).matches()) {
                    String serviceHome;
                    try {
                        serviceHome = f.getCanonicalPath();
                    } catch (IOException e) {
                        LOG.error("Fail to get path of service {}", name);
                        continue;
                    }
                    ff.add(serviceHome);
                }
            }
        }
        return ff;
    }

    /**
     * 返回默认的服务目录，工作目录下的services子目录
     * @param service 服务名
     * @return services/service子目录
     */
    public static String serviceHome(String service) {
        return FileUtil.addPath(ServicesDirs[0], service);
    }
    
    public static String serviceHome(String service, int subNo) {
        return FileUtil.addPath(ServicesDirs[subNo], service);
    }
    
    /**
     * 返回公司对应的数据库编号
     * 在公有云一个webdb实例会服务多个公司，通过dbNo区分，
     * 在私有云，dbNo固定为LOCAL_DBNO
     * @return 数据库编号
     */
    public CompletableFuture<Integer> getDbNo(int cid) {
        if(PartitionConfig.instance().isPrivate()
          || this.type != ServiceType.COMPANY) { //在公司环境或非公司服务，按服务区分dbNo
            Integer v = DbNos.get(this.name);
            if(v != null) {
                return CompletableFuture.completedFuture(v);
            }

            ServiceReqBuilder req = new ServiceReqBuilder(this, IConst.SERVICE_BIOS)
                .url("/service/dbno")
               .cid(cid).appToken("*").traceId("get_service_dbno_" + this.name);
            return BiosClient.get(req).thenApplyAsync(hr -> {
                if(hr.code != RetCode.OK) {
                    LOG.debug("Fail to get dbno of service {}, use local dbno as default", this.name);
                    DbNos.put(this.name, IDBConst.LOCAL_DBNO); //无论如何都存一个，防止被攻击时，压力传到后方
                    return IDBConst.LOCAL_DBNO;
                }
                int no = ValParser.getAsInt(hr.data, "dbNo", IDBConst.LOCAL_DBNO);
                DbNos.put(this.name, no);
                return no;
            }, IThreadPool.Pool);
        }

        String key = Integer.toString(cid);
        Integer v = DbNos.get(key);
        if(v != null) {
            return CompletableFuture.completedFuture(v);
        }
        /*
         * 设计的假设前提是单个公司的请求不会太大，
         * 所以此处并没有防止多个请求同时发起http请求的问题，
         * 即使出现，也不会对系统造成大的影响。
         * 这里唯一要担心的是攻击行为会导致压力被传给company服务
         */
        ServiceReqBuilder req = new ServiceReqBuilder(this, IConst.SERVICE_COMPANY);
        req.url("/webdb/dbno?id=" + cid)
           .cid(cid).appToken("*").traceId("get_company_dbno_" + cid);
        return ServiceClient.serviceGet(req).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.debug("Fail to get dbno of company {}", cid);
                DbNos.put(key, -1); //无论如何都存一个，防止被攻击时，压力传到后方
                return -1;
            }
            int no = ValParser.getAsInt(hr.data, "dbNo", -1);
            DbNos.put(key, no);
            return no;
        }, IThreadPool.Pool);
    }
    
    public CompletableFuture<HandleResult> initWebDb(int cid, List<Object> dbDefines) {
        if (dbDefines == null) {
            LOG.error("Invalid database config file of service `{}`", this.name);
            return HandleResult.future(RetCode.INTERNAL_ERROR, "invalid database.cfg");
        }
        if(dbDefines.isEmpty()) { //文件不存在
            return HandleResult.future();
        }
        //调用时，需要保证有正确的cid头
        return getDbNo(cid).thenComposeAsync(dbNo -> {
            if(dbNo < IDBConst.LOCAL_DBNO) {
                return HandleResult.future(RetCode.INTERNAL_ERROR, "dbNo not set");
            }
            return initWebDb(cid, dbNo, dbDefines);
        }, IThreadPool.Pool);
    }
    
    public CompletableFuture<HandleResult> initWebDb(int cid) {
        List<Object> dbDefines = loadDbDefines();
        return initWebDb(cid, dbDefines);
    }
    
    public List<Object> loadDbDefines() {
        String dbCfgFileName = FileUtil.addPath(this.homeDir, IConst.DATABASE_CONFIG_FILE);
        File dbCfgFile = new File(dbCfgFileName);
        if(!dbCfgFile.exists()) { //有数据库配置文件时，才需初始化数据库
            return new ArrayList<>();
        }
        List<Object> dbDefines = JsonUtil.jsonFileToList(new File(dbCfgFileName), true);
        
        return dbDefines != null ? dbDefines : new ArrayList<>();
    }
    
    private CompletableFuture<HandleResult> initWebDb(int cid, int dbNo, List<Object> dbDefines) {
        int partId = PartitionConfig.instance().partition;
        String appToken = BiosClient.appToken(this, IConst.SERVICE_WEBDB, "*");
        List<CompletableFuture<HandleResult>> actions = new ArrayList<>();
        Map<String, HandleResult> results = new HashMap<>();
        LOG.info("Create {}'s db of cid({}) in partition {}", this.name, cid, partId);
        StringBuilder dbs = new StringBuilder(4096);
        
        /*
         *dbDefines:[
         * {name:"xxx",type:"rdb",versions:[
         *     {version:"xxx",sqls:["sql1","sql2"...]},...
         *   ]
         * }
         * ...
         *]
         */
        //模拟请求，从backend发起，调用当前服务
        AccessToken token = this.tokenWorker.create(partId,
                IConst.SERVICE_BACKEND, this.name,
                AccessToken.EXT_FEATURE_ALL + cid,
                IOAuth.TOKENTYPE_SERVICE);
        Map<String, String> headers = Map.of(
                IConst.HEAD_CID, Integer.toString(cid),
                IConst.HEAD_TRACE_ID, this.name + "_init",
                IConst.HEAD_ACCESS_TOKEN, token.generate());
        Map<String, Object> params = MapBuilder.of(
                IConst.EMBEDED_TOKEN_CALLER, token.caller,
                IConst.EMBEDED_TOKEN_CALLEE, token.callee,
                IConst.EMBEDED_TOKEN_PARTITION, token.partition,
                IConst.EMBEDED_TOKEN_EXT, token.ext);
        AbsServerRequest req = new NullServerRequest(IConst.METHOD_GET, "init",
                headers, params, this, token);
        req.setServiceInfo(this);
        
        for(Object o : dbDefines) { //逐个db处理
            Map<String, Object> dbCfg = ValParser.parseObject(o);
            String dbName;

            if(dbCfg == null || (dbName = ValParser.getAsStr(dbCfg, "name")) == null) {
                actions.add(HandleResult.future(RetCode.WRONG_PARAMETER, "invalid db name"));
                continue;
            }

            if(dbs.length() > 0) {
                dbs.append(',');
            }
            dbs.append(dbName);
            
            ServiceReqBuilder builder = new ServiceReqBuilder(this, IConst.SERVICE_BIOS)
                .url("/db/nodes?dbNo=" + dbNo) //私有云的都用LOCAL_DBNO
                .traceId(this.name + "_initwebdb")
                .cid(cid)
                .appToken("*")
                .appendPara("partId", Integer.toString(partId));
            
            CompletableFuture<HandleResult> cf = BiosClient.get(builder).thenComposeAsync(hr -> {
                //return CompletableFuture.completedFuture(HandleResult.OK);
                List<Object> nodes; //每行包括addr,sharding,slaves,ver,level
                if(hr.code != RetCode.OK || hr.data == null
                   || (nodes = ValParser.getAsList(hr.data, "nodes")) == null) {
                    LOG.error("Fail to get dbnodes of {}.{}, result:{}", this.name, dbName, hr.brief());
                    return CompletableFuture.completedFuture(hr);
                }
                LOG.debug("Initialize dbs for service `{}` on {}", this.name, nodes);
                String type = ValParser.getAsStr(dbCfg, "type");
                String version = null;
                String tableDef = IConst.EMPTY_STR;
                if(type.equals(IDBConst.RDB)) {//name,type,version,versions
                    List<Object> versions = ValParser.getAsList(dbCfg, "versions");
                    if(versions == null || versions.isEmpty()) {
                        return HandleResult.future(RetCode.INTERNAL_ERROR, "invalid db sqls");
                    }
                    String s = JsonUtil.objToJson(versions);
                    version = ValParser.getAsStr(dbCfg, "version");
                    tableDef = AbsDBWorker.compileScript(s, req, new HashMap<>());
                }
                //向每个实例都发起请求，初始化数据库
                return initOneWebDB(cid, dbNo, dbName, type, tableDef,
                        version, nodes, appToken);
            }, IThreadPool.Pool).whenCompleteAsync((hr, e) -> {
                if(e != null) {
                    LOG.error("Fail to init tables of {}.{} in {}", cid, this.name, dbName, e);
                    results.put(dbName, new HandleResult(RetCode.INTERNAL_ERROR, "fail to init " + dbName));
                } else {
                    results.put(dbName, hr);
                }
            }, IThreadPool.Pool);
            actions.add(cf);
        }

        return CompletableFuture.allOf(actions.toArray(new CompletableFuture<?>[dbDefines.size()])).thenComposeAsync(_v -> {
            if(this.type == ServiceType.COMPANY) { //非公司服务，在每个实例重启时会执行初始化，此处不必处理
                List<ApiMethod> inits = this.getInitApis();
                if(inits != null && !inits.isEmpty()) {
                    AbsServerResponse resp = req.createResponse();
                    for(ApiMethod am : inits) {
                        LOG.info("Execute_init {}.{},token:{}", this.name, am.apiInfo.url, req.token().tokenTypeName());
                        am.execute(req, resp); //调用服务的所有初始化接口
                    }
                }
            }

            Map<String, Object> d = new HashMap<>();
            for(Map.Entry<String, HandleResult> e : results.entrySet()) {
                HandleResult hr = e.getValue();
                if(hr.code != RetCode.OK) {
                    return CompletableFuture.completedFuture(hr);
                }
                d.put(e.getKey(), hr.toMap());
            }
            d.put("dbs", dbs.toString());

            return HandleResult.future(d);
        }, IThreadPool.Pool);
    }

    /**
     *
     * @param cid 公司id
     * @param dbNo 数据库编号
     * @param db 数据库名
     * @param type 类型
     * @param tableDef 数据库定义sqls，包含多个版本，
     *  [{version:"xxx",sqls:["sql1","sql2"...]},...]
     * @param version 目标版本号
     * @param nodes 需要初始化的节点
     * @param appToken app token
     * @return 执行结果
     */
    private CompletableFuture<HandleResult> initOneWebDB(int cid, int dbNo, String db,
            String type, String tableDef, String version,
            List<Object> nodes, String appToken) {
        List<CompletableFuture<HandleResult>> actions = new ArrayList<>();
        Map<String, HandleResult> results = new HashMap<>();
        ServiceReqBuilder request = new ServiceReqBuilder(this, IConst.SERVICE_WEBDB)
                .url("/om/initTables").traceId(this.name + "_inittables").cid(cid)
                .token(appToken)
                .put(IDBConst.DB_REQ_DB, db)
                .put(IDBConst.DB_REQ_DBNO, dbNo) //私有云只有一个dbno
                .put("type", type)
                .put("tabledef", tableDef);
        if(!StringUtil.isEmpty(version)) {
            request.put("version", version); //如果传了version，则会判断版本的规则
        }
        
        for(Object o : nodes) { //每个db需要发送到所有的节点
            Map<String, Object> node = ValParser.parseObject(o);
            String addr = ValParser.getAsStr(node, "addr"); //不处理数据库从节点
            LOG.debug("init tables of {}.{} on {},cid:{}", this.name, db, addr, cid);
            NodeAddress dn = new NodeAddress(this.name, addr, StringUtil.verToInt(version), 0);
            CompletableFuture<HandleResult> cf = ServiceClient.servicePost(dn, request).whenCompleteAsync((initHr, e) -> {
                if(e != null) {
                    LOG.error("Fail to init tables of {}.{} on {}", this.name, db, addr, e);
                    results.put(addr, new HandleResult(RetCode.INTERNAL_ERROR, "fail to init " + db));
                } else {
                    results.put(addr, initHr);
                }
            }, IThreadPool.Pool);

            actions.add(cf);
        }

        return CompletableFuture.allOf(actions.toArray(new CompletableFuture<?>[nodes.size()])).thenComposeAsync(_v -> {
            Map<String, Object> d = new HashMap<>();
            HandleResult hr;
            
            for(Map.Entry<String, HandleResult> e : results.entrySet()) {
                hr = e.getValue();
                if(hr.code != RetCode.OK) {//只要有一个失败则整体失败
                    LOG.error("Fail to init DB(cid:{},dbNo:{},service:{},db:{})",
                            cid, dbNo, this.name, db);
                    return CompletableFuture.completedFuture(hr);
                }
                d.put(e.getKey(), hr.toMap());
            }
            return HandleResult.future(d);
        }, IThreadPool.Pool);
    }
    
    /**
     * 在bios中注册服务的信息
     * @param cid 公司id
     * @param dbDefines 数据库定义内容
     * @param pwdToken 公司密码或运维totp密码，不经过sha256运算
     * @return 注册结果
     */
    public CompletableFuture<HandleResult> register(int cid, List<Object> dbDefines, String pwdToken) {
        LOG.info("Register service:{},cid:{},type:{}", this.name, cid, this.type.name());
        //注册服务，及添加必须的依赖项
        //si为OM服务，appTokenWorker必不为空
        String key = this.tokenWorker.key().toString();

        ServiceReqBuilder request = new ServiceReqBuilder(this, IConst.SERVICE_BIOS)
            .token(pwdToken)
            .url("/init/register")
            .traceId(this.name + "_register")
            .put("service", this.name) //从token中无法获得调用方，所有必须传入
            .put("partId", PartitionConfig.instance().partition)
            .put("addr", ChannelConfig.instance().localHttpAddr())
            .put("dependencies", this.getDpdMaps())
            .put("dbDefines", dbDefines)
            .put("key", key)
            .put("type", this.type.name())
            .put("dispName", this.displayName)
            .put("common", this.type != ServiceType.COMPANY)
            .put("visible", this.visible ? 1 : 0) //客户端路由中是否可见
            .cid(cid);

        return BiosClient.post(request);
    }
    
    public HandleResult initLocalDb(String serverHomeDir, List<Object> dbCfgs) {
        /*
         * 以下实现出现过死锁。
         * 最初的SqliteWorker.localInstance中没有判断是否已经存在，而是直接创建对象，
         * 导致同一个数据库文件出现两个写连接，同时写sqlite的时候就会死锁。
         * 在非本地数据库中，SqliteWorker、TreeDBWorker、SearchDBWorker
         * 对象分别记录在不同的map中，且TreeDBWorker、SearchDBWorker在创建时，
         * 都调用AbsRDBWorker.instance获得RDB实例，所以不会出现冲突的问题。
         */
        String type;
        String db;
        for(Object o : dbCfgs) {
            Map<String, Object> dbCfg = ValParser.parseObject(o);
            if(dbCfg == null || dbCfg.isEmpty()) {
                return new HandleResult(RetCode.INTERNAL_ERROR, "no appropriate local db configuration");
            }
            type = ValParser.getAsStr(dbCfg, "type").toLowerCase();
            db =  ValParser.getAsStr(dbCfg, "name");
            if(type.equals(IDBConst.TREEDB)) {
                TreeDBWorker tdw = TreeDBWorker.localInstance(this, db, null);
                if(tdw == null) {
                    LOG.error("Can't create TreeDBWorker `{}.{}`", this.name, db);
                    return new HandleResult(RetCode.INTERNAL_ERROR, "fail to create tree db");
                }
                this.addLocalDBWorker(tdw, tdw.getCoreDb());
                LOG.info("Create TreeDBWorker {}.{}", this.name, db);
            } else if(type.equals(IDBConst.SEARCHDB)) {
                AbsSearchDBWorker sdw = SearchDBWorker.localInstance(this, db, null);
                if(sdw == null) {
                    LOG.error("Can't create SearchDBWorker `{}.{}`", this.name, db);
                    return new HandleResult(RetCode.INTERNAL_ERROR, "fail to create search db");
                }
                AbsSearchDBWorker.init(FileUtil.addPath(serverHomeDir, IConst.SYS_CONF_DIR));
                this.addLocalDBWorker(sdw, sdw.getCoreDb());
                LOG.info("Create SearchDBWorker {}.{}", this.name, db);
            } else {
                AbsRDBWorker rdw = SqliteWorker.localInstance(this, db, null);
                if(rdw == null || !rdw.createRDB(this.name, dbCfg)) {
                    LOG.error("Can't create SqliteWorker `{}.{}`", this.name, db);
                    return new HandleResult(RetCode.INTERNAL_ERROR, "no appropriate local db configuration");
                }
                this.addLocalDBWorker(rdw, null);
                LOG.info("Create SqliteWorker {}.{}", this.name, db);
            }
        }
        
        return HandleResult.OK;
    }
    
    public HandleResult initLocalDb(String serverHomeDir) {
        LOG.info("{}.watcher.initLocalDb()", this.name);
        File dbCfgFile = new File(FileUtil.addPath(this.homeDir, IConst.LOCAL_DATABASE_CONFIG_FILE));
        if(!dbCfgFile.exists()) { //读取本地数据库配置，立刻创建并初始化
            return HandleResult.OK;
        }

        //初始化本地数据库，在address服务中使用过，如果不存在则直接在本地创建并初始化
        List<Object> dbCfgs = JsonUtil.jsonFileToList(dbCfgFile, true);
        if(dbCfgs == null || dbCfgs.isEmpty()) {
            LOG.error("Invalid database config file `{}`", dbCfgFile);
            return new HandleResult(RetCode.INTERNAL_ERROR, "invalid " + IConst.LOCAL_DATABASE_CONFIG_FILE);
        }
        LOG.info("load {}", dbCfgFile);
        return initLocalDb(serverHomeDir, dbCfgs);
    }
}
