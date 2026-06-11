package cn.net.zhijian.mesh.frm.service.backend;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.ServiceDNS;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.abs.AbsAssets;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsTokenChecker;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ProcessInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ResponseInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.tokenchecker.TokenCheckers;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.mesh.frm.intf.IServiceWatcher;
import cn.net.zhijian.mesh.frm.method.ApiMethod;
import cn.net.zhijian.mesh.frm.tokenworker.EccTokenWorker;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.UrlPathInfo;

/**
 * Agent是OM在每个实例上的代理，
 * 负责处理OM的安装、卸载等请求，对实例进行维护
 * @author flyinmind of csdn.net
 *
 */
public abstract class BackendBase extends AbsProcessor {
    protected static final String PARA_SERVICE = "service";

    protected final IServiceServer server;
    
    public BackendBase(IServiceServer vServer, ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
        this.server = vServer;
    }
 
    /**
     * 参数定义列表
     * @return 参数列表
     */
    public RequestInfo getRequestInfo() {
        return new RequestInfo();
    }
    
    /**
     * 创建backend服务信息
     * @param serverWorkHome 服务器工作的根目录
     */
    public static void createService(String serverWorkHome) throws IOException {
        ServiceInfo si = ServiceInfo.createBuiltIn(
                ServiceInfo.serviceHome(SERVICE_BACKEND),
                SERVICE_BACKEND,
                //loadResOfAllServices会从bios加载密钥对，如果没有会产生新的
                EccTokenWorker.nullTokenWorker(IOAuth.SIGNTYPE_APPKEY),
                null, IServiceWatcher.DefaultWatcher); //虚拟的company服务
        InputStream in = AbsAssets.instance().open("backend_database.loc.cfg");
        List<Object> dbDefines = JsonUtil.jsonStreamToList(in);
        si.initLocalDb(serverWorkHome, dbDefines);

        //在此加入dns，使得其他服务优先访问本实例的backend
        String[] nodes = new String[] {ChannelConfig.instance().localHttpAddr()};
        ServiceDNS dns = ServiceDNS.create(SERVICE_BACKEND, nodes, 0);
        ServiceClient.setLocalDns(SERVICE_BACKEND, dns);
        ServiceClient.setBackendService(si);
    }
    
    public static void createApis(IServiceServer server) {
        ServiceInfo si = ServiceClient.backendService();
        //查询单例实例的服务状态：/backend/api/servicestate
        String procName = "servicestate";
        UrlPathInfo url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
        ApiInfo apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, true);
        ServiceState serviceState = new ServiceState(server, si, apiInfo, procName);
        ApiMethod am = new ApiMethod(si, apiInfo, new RequestInfo(), new ResponseInfo(null),
                new ProcessInfo(serviceState), TokenCheckers.Backend, null);
        server.addApi(am);

        //查询单例实例的日志文件列表：/backend/api/listlogs
        procName = "listlogs";
        url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
        apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, true);
        ListLogs listLogs = new ListLogs(si, apiInfo, procName);
        am = new ApiMethod(si, apiInfo, new RequestInfo(), new ResponseInfo(null),
                new ProcessInfo(listLogs), TokenCheckers.Backend, null);
        server.addApi(am);
        
        //保活探测：/backend/api/checkup
        //在命令行中可以探测此接口，判断服务器是否还在运行
        url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(API_CHECKUP);
        String apiUrl = url.toString().toLowerCase();
        PartitionConfig partCfg = PartitionConfig.instance();
        if(server.getApi(apiUrl) == null) { //没有自带checkup，则以DefaultCheckUp代替
            apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, false);
            AbsProcessor cu = new AbsProcessor(si, apiInfo, "check_up") {
                @Override
                protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
                    Map<String, Object> data = MapBuilder.of(
                            "mode", partCfg.mode.name(),
                            "timeZone", partCfg.timeZone,
                            "companyId", CompanyInfo.instance().id); //多个公司共服务器，也只需要检查第一个就可以
                    data.put("startupAt", DateUtil.utcToLocale(IServiceServer.startupAt));
                    data.put("interval", DateUtil.interval2Str(System.currentTimeMillis() - IServiceServer.startupAt));
                    return futureResult(data);
                }
            };
            am = new ApiMethod(si, apiInfo, new RequestInfo(), new ResponseInfo(null),
                    new ProcessInfo(cu), null, null);
            server.addApi(am);
        }

        //在实例上安装服务：/backend/api/install
        AbsTokenChecker MNT = TokenCheckers.Mnt;
        procName = "install";
        url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
        apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_POST, url, null, true);
        InstallService install = new InstallService(server, si, apiInfo, procName);
        am =  ApiMethod.generate(install, apiInfo, install.getRequestInfo(), MNT, null);
        server.addApi(am);

        //在实例上卸载服务：/backend/api/uninstall
        procName = "uninstall";
        url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
        apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_POST, url, null, true);
        UnInstallService unInstall = new UnInstallService(server, si, apiInfo, procName);
        am = ApiMethod.generate(unInstall, apiInfo, unInstall.getRequestInfo(), MNT, null);
        server.addApi(am);

        //更新bios服务实例列表：/backend/api/updatebiossrvs
        procName = "updatebiossrvs";
        url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
        apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_POST, url, null, true);
        NotifyBiosSrvs updateBios = new NotifyBiosSrvs(server, si, apiInfo, procName);
        am = ApiMethod.generate(updateBios, apiInfo, updateBios.getRequestInfo(), MNT, null);
        server.addApi(am);

        //返回节点上当前正在运行的服务列表, /backend/api/services
        procName = API_SERVICE_LIST;
        url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
        apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, false);
        ServiceList slp = new ServiceList(server, si, apiInfo, procName);
        am = new ApiMethod(si, apiInfo, new RequestInfo(), new ResponseInfo(null),
                new ProcessInfo(slp), null, null);
        server.addApi(am);

        //添加下载日志的处理
        server.addFile('/' + si.name + "/downloadlog", new DownloadLog(si, LogUtil.getLogPath()));
    }
}