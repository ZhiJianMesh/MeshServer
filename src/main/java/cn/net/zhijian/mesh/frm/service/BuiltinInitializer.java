package cn.net.zhijian.mesh.frm.service;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.ServiceDNS;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.abs.AbsFileMethod;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsTokenChecker;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.ProcessInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ResponseInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo.Dependency;
import cn.net.zhijian.mesh.frm.config.para.ParameterInfo;
import cn.net.zhijian.mesh.frm.config.tokenchecker.TokenCheckers;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.mesh.frm.intf.IServiceWatcher;
import cn.net.zhijian.mesh.frm.method.ApiMethod;
import cn.net.zhijian.mesh.frm.method.StaticFileMethod;
import cn.net.zhijian.mesh.frm.service.aide.ApiList;
import cn.net.zhijian.mesh.frm.service.aide.ClientInfo;
import cn.net.zhijian.mesh.frm.service.aide.DefaultCheckUp;
import cn.net.zhijian.mesh.frm.service.aide.FileList;
import cn.net.zhijian.mesh.frm.service.aide.InitDb;
import cn.net.zhijian.mesh.frm.service.aide.RemoveDb;
import cn.net.zhijian.mesh.frm.service.aide.SqlExecutor;
import cn.net.zhijian.mesh.frm.service.company.AdminToken;
import cn.net.zhijian.mesh.frm.service.company.Command;
import cn.net.zhijian.mesh.frm.service.company.CompanyName;
import cn.net.zhijian.mesh.frm.service.company.CompanyToken;
import cn.net.zhijian.mesh.frm.service.company.Services;
import cn.net.zhijian.mesh.frm.service.company.SqlExecute;
import cn.net.zhijian.mesh.frm.service.httpdns.DnsLookup;
import cn.net.zhijian.mesh.frm.service.httpdns.DnsProbe;
import cn.net.zhijian.mesh.frm.service.httpdns.Entrance;
import cn.net.zhijian.mesh.frm.tokenworker.EccTokenWorker;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.UrlPathInfo;

/**
 * 在私有云单例运行或集群中网关运行时，没有httpdns,company服务，
 * 本包中的对象在私有云环境模拟公有云接口，使得client无论访问公有云还是私有云，接口都是一样的。
 * 公有云对应的接口是放在company、httpdns服务中的
 * 在所有本地接口已startup之后才会调用load
 */
public final class BuiltinInitializer implements IConst {
    private static final Logger LOG = LogUtil.getInstance();

    /**
     * 私网环境，增加兼容接口
     * @param server 服务器
     * @throws MeshException 
     */
    public static void createCompatibleServices(IServiceServer server) {
        LOG.info("create BUILTIN service {}", SERVICE_HTTPDNS);
        ServiceInfo httpdnsService = ServiceInfo.createBuiltIn(
            ServiceInfo.serviceHome(SERVICE_HTTPDNS),
            SERVICE_HTTPDNS,
            //loadResOfAllServices会从bios加载密钥对，如果没有会产生新的
            EccTokenWorker.nullTokenWorker(IOAuth.SIGNTYPE_APPKEY),
            null, IServiceWatcher.DefaultWatcher); //httpdns服务
        server.addService(httpdnsService);
        
        LOG.info("create BUILTIN service {}", SERVICE_COMPANY);
        Dependency[] dps = new Dependency[] {
            new Dependency(SERVICE_SEQID, "0.1.0", "*", false),
            new Dependency(SERVICE_KEYSTORE, "0.1.0", "*", false)
        };
        ServiceInfo companyService = ServiceInfo.createBuiltIn(
                ServiceInfo.serviceHome(SERVICE_COMPANY),
                SERVICE_COMPANY,
                //loadResOfAllServices会从bios加载密钥对，如果没有会产生新的
                EccTokenWorker.nullTokenWorker(IOAuth.SIGNTYPE_APPKEY),
                dps, IServiceWatcher.DefaultWatcher); //虚拟的company服务

        server.addService(companyService);
    }
    
    public static void loadCompatibleApis(IServiceServer server) throws MeshException {
        LOG.info("Create compatible apis for singleton or cluster mode");

        try {
            LOG.info("createHttpDnsApis");
            createHttpDnsApis(server);

            LOG.info("createCompanyApis");
            createCompanyApis(server);
    
            //在此加入dns，使得其他服务优先访问本实例的httpdns、company
            String[] nodes = new String[] {ChannelConfig.instance().localHttpAddr()};
            ServiceDNS dns = ServiceDNS.create(SERVICE_HTTPDNS, nodes, 0);
            ServiceClient.setLocalDns(SERVICE_HTTPDNS, dns);
            dns = ServiceDNS.create(SERVICE_COMPANY, nodes, 0);
            ServiceClient.setLocalDns(SERVICE_COMPANY, dns);
        } catch (Exception e) {
            LOG.error("Fail to generate keypairs", e);
            throw new MeshException("fail to generate keypairs", e);
        }
    }

    private static ServiceInfo createHttpDnsApis(IServiceServer server) {
        RequestInfo emptyRequestInfo = new RequestInfo();
        UrlPathInfo url;
        ApiInfo apiInfo;
        ApiMethod am;

        ServiceInfo serviceHttpDns = server.getService(SERVICE_HTTPDNS);

        //单例的情况，刚启动，服务还没有上报到bios，即使是多实例，当前节点的服务也没有上报
        //所以在此不能调用HttpDnsBase.initRouteConfig，在客户端请求时再调用

        //在安卓端没有dns服务，所以在程序中创建此类接口
        // /httpdns/api/probe 端侧查询所有可用的服务及其地址
        url = new UrlPathInfo(serviceHttpDns.name).push(SERVICE_URL_API).push("probe");
        apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, false);
        AbsProcessor dnsProbe = new DnsProbe(serviceHttpDns, apiInfo, server);
        am = ApiMethod.generate(dnsProbe, apiInfo, emptyRequestInfo, null, null);
        server.addApi(am);

        // /httpdns/api/lookup，私有云中，查询单个服务的地址，单例时，所有服务解析的结果都是本机
        url = new UrlPathInfo(serviceHttpDns.name).push(SERVICE_URL_API).push("lookup");
        apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, false);
        AbsProcessor lookup = new DnsLookup(serviceHttpDns, apiInfo, server);
        ParameterInfo[] params = new ParameterInfo[]{
            new ParameterInfo.Builder("service", ParameterInfo.TYPE_STRING).setRegular("^[a-zA-Z0-9_]{1,30}$").build(),
            new ParameterInfo.Builder("id", ParameterInfo.TYPE_INT).build()
        };
        am = ApiMethod.generate(lookup, apiInfo, new RequestInfo(params), null, null);
        server.addApi(am);

        // /httpdns/api/company/entrance，查询私有云内网、外网入口地址以及公司名称
        url = new UrlPathInfo(serviceHttpDns.name).push(SERVICE_URL_API).push("company").push("entrance");
        apiInfo = new ApiInfo("company", METHOD_GET, url, null, false);
        AbsProcessor entrance = new Entrance(serviceHttpDns, apiInfo);
        am = ApiMethod.generate(entrance, apiInfo, emptyRequestInfo, null, null);
        server.addApi(am);

        loadAide(serviceHttpDns, server);

        return serviceHttpDns;
    }

    private static ServiceInfo createCompanyApis(IServiceServer server) {
        RequestInfo emptyRequestInfo = new RequestInfo();
        UrlPathInfo url;
        ApiInfo apiInfo;
        ApiMethod am;

        ServiceInfo companyService = server.getService(SERVICE_COMPANY);

        String homeDir = companyService.homeDir;
        // /company/api/service/list，用于在端侧显示私有云开启的所有服务，用于兼容公有云同名接口
        url = new UrlPathInfo(companyService.name).push(SERVICE_URL_API).push("service").push("list");
        apiInfo = new ApiInfo("service", METHOD_GET, url, null, false);
        AbsProcessor serviceList = new Services(server, companyService, apiInfo);
        am = ApiMethod.generate(serviceList, apiInfo, emptyRequestInfo, null, null);
        server.addApi(am);

        // /company/logo，用于获取公司logo，设置后，存储在单例的/services/company/file/目录下
        // 单例模式下无company服务，但是要创建/services/company/logos目录
        // 在安装服务器中，JsCompany.saveLogo将logo写入这个目录下
        // 单例的/company/logo解开就读取这个文件，云侧的读取“公司ID.txt”的文件
        // 端侧下载logo时，从公司的服务器下载，这样不必消耗云侧的带宽
        String logosDir = FileUtil.addPath(homeDir, "logos");
        FileUtil.createDir(logosDir); //创建一个空的服务根目录，用于存放logo
        url = new UrlPathInfo(companyService.name).push("logo");
        String logoFile = FileUtil.addPath(logosDir, "logo.txt");
        String contentType = AbsFileMethod.getContentType("txt");
        byte[] defaultLogo = CompanyName.EMPTY_LOGO.getBytes(DEFAULT_CHARSET);
        server.addFile(url.toString(), new StaticFileMethod(companyService, logoFile, contentType, defaultLogo));

        //ServiceInfo companyService = services.get(SERVICE_COMPANY);
        // /company/api/name，用于获得公司name/logo，兼容云侧的同名接口
        url = new UrlPathInfo(companyService.name).push(SERVICE_URL_API).push("name");
        apiInfo = new ApiInfo("company", METHOD_GET, url, null, false);
        ParameterInfo[] parameters = new ParameterInfo[]{
            new ParameterInfo.Builder("cid", ParameterInfo.TYPE_INT).build()
        };
        AbsProcessor companyName = new CompanyName(companyService, apiInfo, logoFile);
        am = ApiMethod.generate(companyName, apiInfo, new RequestInfo(parameters), null, null);
        server.addApi(am);

        //ServiceInfo companyService = services.get(SERVICE_COMPANY);
        // /company/api/token，用于在端侧输入公司密码获得公司token，访问云侧管理接口
        url = new UrlPathInfo(companyService.name).push(SERVICE_URL_API).push("token");
        apiInfo = new ApiInfo("company", METHOD_POST, url, null, false);
        parameters = new ParameterInfo[]{
            new ParameterInfo.Builder("pwd", ParameterInfo.TYPE_STRING).setRegular("^.{43}$").build(), //公司密码，需要sha256运算后传递
            new ParameterInfo.Builder("services", ParameterInfo.TYPE_STRING).setRegular("^[a-zA-Z0-9_]{1,30}$").asList().build()
        };
        AbsProcessor companyToken = new CompanyToken(companyService, apiInfo);
        am = ApiMethod.generate(companyToken, apiInfo, new RequestInfo(parameters), null, null);
        server.addApi(am);
        
        //ServiceInfo companyService = services.get(SERVICE_COMPANY);
        // /company/api/webdb/admintoken，用于webdb中获得公司token，访问云侧管理接口
        url = new UrlPathInfo(companyService.name).push(SERVICE_URL_API).push("webdb").push("admintoken");
        apiInfo = new ApiInfo("company", METHOD_POST, url, null, true);
        parameters = new ParameterInfo[]{
            new ParameterInfo.Builder("services", ParameterInfo.TYPE_STRING).setRegular("^[a-zA-Z0-9_]{1,30}$").asList().build()
        };
        AbsProcessor backupInfo = new AdminToken(companyService, apiInfo);
        AbsTokenChecker appChecker = TokenCheckers.getChecker("APP-webdb");
        am = ApiMethod.generate(backupInfo, apiInfo, new RequestInfo(parameters), appChecker, null);
        server.addApi(am);

        // /company/api/command
        url = new UrlPathInfo(companyService.name).push(SERVICE_URL_API).push("command");
        apiInfo = new ApiInfo("root", METHOD_POST, url, null, true);
        parameters = new ParameterInfo[]{
            new ParameterInfo.Builder("cmd", ParameterInfo.TYPE_STRING).setRegular("^[a-z|A-Z|0-9]{1,30}$").build()
        };
        AbsProcessor command = new Command(server, companyService, apiInfo);
        am = ApiMethod.generate(command, apiInfo, new RequestInfo(parameters), TokenCheckers.Backend, null);
        server.addApi(am);

        //以服务的身份执行数据库脚本：/company/api/sqlexec
        //提供给管理台以服务的身份执行sql，以COMPANY或BACKEND密码验证权限
        url = new UrlPathInfo(companyService.name).push(SERVICE_URL_API).push("sqlexec");
        apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_POST, url, "admin", true);
        SqlExecute sqlExec = new SqlExecute(companyService, apiInfo, "sqlexec");
        am = new ApiMethod(companyService, apiInfo, SqlExecute.getRequestInfo(), new ResponseInfo(null),
                new ProcessInfo(sqlExec), TokenCheckers.Backend, null);
        server.addApi(am);

        loadAide(companyService, server); //产生初始化等接口

        return companyService;
    }
    
    public static int loadAide(ServiceInfo si, IServiceServer server) {
        int count = 0;
        //service/api/apis:返回服务端接口列表，及每个接口的基本信息
        String procName = API_SERVICE_APILIST;
        UrlPathInfo url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
        ApiInfo apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, false);
        ApiList alp = new ApiList(server, si, apiInfo);
        ApiMethod am = new ApiMethod(si, apiInfo, new RequestInfo(), new ResponseInfo(null),
                new ProcessInfo(alp), null, null);
        server.addApi(am);
        count++;
        
        //service/api/files:返回服务端文件列表，及每个文件的contenttype
        procName = API_SERVICE_FILELIST;
        url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
        apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, false);
        FileList flp = new FileList(server, si, apiInfo);
        am = new ApiMethod(si, apiInfo, new RequestInfo(), new ResponseInfo(null),
                new ProcessInfo(flp), null, null);
        server.addApi(am);
        count++;

        /*
         * 返回端侧的基本信息，用在端侧探测新客户端版本，以及运维平台保活探测
         * 内容从client.cfg中读取，如果此文件不存在，则返回不支持
         * /service/api/client_info 
         */
        url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(API_CLIENT_INFO);
        apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, false);
        ClientInfo cip = new ClientInfo(si, apiInfo, API_CLIENT_INFO);
        am = new ApiMethod(si, apiInfo, new RequestInfo(), new ResponseInfo(null),
                new ProcessInfo(cip), null, null);
        server.addApi(am);
        count++;
        
        //保活探测：/service/api/checkup
        //在ServiceClient中，如果一个节点故障，会定期调用此接口探测节点是否恢复
        url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(API_CHECKUP);
        String apiUrl = url.toString().toLowerCase();
        if(server.getApi(apiUrl) == null) { //没有自带checkup，则以DefaultCheckUp代替
            apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, false);
            DefaultCheckUp dcu = new DefaultCheckUp(si, apiInfo);
            am = new ApiMethod(si, apiInfo, new RequestInfo(), new ResponseInfo(null),
                    new ProcessInfo(dcu), null, null);
            server.addApi(am);
            count++;
        }
        
        if(si.hasDb) {
            //初始化数据库:/service/api/initdb
            procName = API_SERVICE_INITDB;
            url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
            apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_PUT, url, null, true);
            InitDb initDb = new InitDb(server, si, apiInfo, procName);
            am = new ApiMethod(si, apiInfo, initDb.getRequestInfo(), new ResponseInfo(null),
                    new ProcessInfo(initDb), TokenCheckers.Backend, null);
            server.addApi(am);
            count++;
            
            //删除数据库:/service/api/removedb
            procName = API_SERVICE_RMVDB;
            url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
            apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_DELETE, url, null, true);
            RemoveDb rmvDb = new RemoveDb(server, si, apiInfo, procName);
            am = new ApiMethod(si, apiInfo, rmvDb.getRequestInfo(), new ResponseInfo(null),
                    new ProcessInfo(rmvDb), TokenCheckers.Backend, null);
            server.addApi(am);
            count++;

            //以服务的身份执行数据库脚本：/service_name/api/sqlexec
            //提供给管理台以服务的身份执行sql，以COMPANY或BACKEND密码验证权限
            procName = "sqlexec";
            url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
            apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_POST, url, "admin", true);
            SqlExecutor sqlExec = new SqlExecutor(si, apiInfo, procName);
            am = new ApiMethod(si, apiInfo, SqlExecutor.getRequestInfo(), new ResponseInfo(null),
                    new ProcessInfo(sqlExec), TokenCheckers.Backend, null);
            server.addApi(am);
            count++;
        }
        return count;
    }
}
