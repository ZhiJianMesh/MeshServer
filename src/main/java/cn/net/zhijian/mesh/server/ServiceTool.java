package cn.net.zhijian.mesh.server;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig.DeployMode;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

public class ServiceTool implements IThreadPool {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String APP_PKG_NAME = "server.zip";

    /**
     * 安装服务
     * 1）查询服务本身信息，及所有依赖服务的信息；
     * 2）从资源网站逐个下载安装包，并解压；
     * 3）如果系统处于运行状态，则重新加载所有服务。
     * @param service 服务名称
     * @param evm 平台运行环境
     * @return 异步执行结果
     */
    public static CompletableFuture<HandleResult> install(String service, String evm) {
        CompanyInfo ci = CompanyInfo.instance();
        AccessToken at = ci.adminToken(IConst.SERVICE_APPSTORE);
        if(at == null) {
            LOG.error("Fail to create company token, may not be registered");
            return HandleResult.future(RetCode.INVALID_TOKEN, "invalid company token");
        }

        int cid = ci.id;
        String token = at.generate();

        Map<String, ServicePackage> packages = new ConcurrentHashMap<>();//服务包信息及依赖服务包信息存于此对象

        return ServiceClient.getCdnServer(service, ci.area(), cid).thenComposeAsync(site -> {
            if (StringUtil.isEmpty(site)) {
                return HandleResult.future(RetCode.OK, "no cdn");
            }
            Map<String, ServiceInfo> installed = installedServices();
            return requiredServices(cid, token, service, evm, installed, packages, site);
        }, Pool).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to get all the required services, result:{}", hr.brief());
                return CompletableFuture.completedFuture(hr);
            }
            LOG.debug("Get all required services");

            Map<String, HandleResult> results = new ConcurrentHashMap<>();
            List<CompletableFuture<HandleResult>> cfs = new ArrayList<>();
            for(ServicePackage pkg : packages.values()) {
                cfs.add(downloadPackage(pkg, ServiceInfo.servicesRoot(), cid, token).thenComposeAsync(dlHr->{
                    if(dlHr.code != RetCode.OK) {
                        return downloadPackage(pkg, ServiceInfo.servicesRoot(), cid, token); //只重试一次
                    }
                    return CompletableFuture.completedFuture(dlHr);
                }, Pool).whenCompleteAsync((dlHr, e) -> {
                    if(e != null) {
                        LOG.error("Fail to download required packages of {}", pkg.service, e);
                        results.put(pkg.service, new HandleResult(RetCode.INTERNAL_ERROR, "fail to download " + pkg.service));
                    } else {
                        results.put(pkg.service, dlHr);
                        LOG.info("Success to download {}", pkg.service);
                    }
                }, Pool));
            }

            return CompletableFuture.allOf(cfs.toArray(new CompletableFuture<?>[0])).thenComposeAsync(_v -> {
                HandleResult failed = null;
                for(HandleResult result : results.values()) {
                    if(result.code != RetCode.OK) {
                        failed = result;
                        break;
                    }
                }
                if(failed != null) {//任何一个失败，则全部回滚，并且返回第一个失败的信息
                    for(Map.Entry<String, ServicePackage> o : packages.entrySet()) {
                        if(o.getValue().isUpdate) { //当前不支持升级服务的回滚
                            continue;
                        }
                        FileUtil.remove(new File(ServiceInfo.serviceHome(o.getKey())));
                    }
                    return CompletableFuture.completedFuture(failed);
                }
                return HandleResult.future();
            }, Pool);
        }, Pool).exceptionally(e -> {
            LOG.error("Fail to install {},evm:{}", service, evm, e);
            return HandleResult.InternalError;
        });
    }

    public static CompletableFuture<HandleResult> downloadPackage(ServicePackage sp, String serviceDir, int cid, String token) {
        long startAt = System.currentTimeMillis();
        String packageZip = FileUtil.addPath(serviceDir, sp.service + ".zip");
        String versionRoot = UrlPathInfo.append(sp.url, new String[] {sp.service, StringUtil.intToVer(sp.ver)});
        String url = UrlPathInfo.append(versionRoot, new String[]{APP_PKG_NAME});
        return HttpClient.download(url, null, packageZip).thenComposeAsync(hr -> {
            ServiceReqBuilder reportReq = ServiceClient.backendReqBuilder(IConst.SERVICE_APPSTORE)
                    .url("/version/report")
                    .cid(cid)
                    .token(token);

            reportReq.put("service", sp.service);
            reportReq.put("url", sp.url);
            reportReq.put("area", CompanyInfo.instance().area());
            reportReq.put("useTime", System.currentTimeMillis() - startAt);

            try {
                if (hr.code != RetCode.OK) {
                    LOG.error("Fail to download {}, result:{}", url, hr.brief());
                    reportReq.put("failed", 1);
                    return CompletableFuture.completedFuture(hr);
                }

                File pkgZipFile = new File(packageZip);
                reportReq.put("size", (int)pkgZipFile.length());
                String digest = FileUtil.digest(pkgZipFile);
                if (digest == null || !digest.equals(sp.digest)) {
                    LOG.error("Fail to download {},invalid digest `'{}'!='{}'`,result:{}", url, digest, sp.digest, hr.brief());
                    reportReq.put("failed", 1);
                    return HandleResult.future(RetCode.DATA_WRONG, "invalid digest");
                }

                String serviceHomeDir = FileUtil.addPath(serviceDir, sp.service);
                FileUtil.remove(new File(serviceHomeDir)); //先删原来的服务路径，避免与原版本混杂
                if (FileUtil.unzipFile(packageZip, serviceHomeDir) <= 0) {
                    LOG.error("Fail to unzip file {}", packageZip);
                    return HandleResult.future(RetCode.DATA_WRONG, "invalid package file");
                }

                FileUtil.removeFile(packageZip);
                reportReq.put("failed", 0);
                return HandleResult.future();
            } catch (Exception e) {
                LOG.error("Fail to download {}", packageZip, e);
                return HandleResult.future(RetCode.INTERNAL_ERROR, "exception " + e.getMessage());
            } finally {
                ServiceClient.cloudPost(reportReq);
            }
        }, Pool);
    }

    /**
     * 递归查询所有依赖的安装包
     * @param cid 公司ID
     * @param token 请求token
     * @param service 服务
     * @param installed 已安装的服务，name->version
     * @param evm 运行环境
     * @param packages 待安装的包
     * @param cdnSite cdn站点
     * @return 异步结果
     */
    public static CompletableFuture<HandleResult> requiredServices(int cid, String token,
            String service, String evm, Map<String, ServiceInfo> installed,
            Map<String, ServicePackage> packages,
            String cdnSite) {
        UrlPathInfo urlInfo = new UrlPathInfo(IConst.SERVICE_URL_API)
                .push("version")
                .push("latest")
                .appendPara("evm", evm, false)
                .appendPara("service", service, false);
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_APPSTORE)
                .url(urlInfo.toString())
                .traceId(IConst.SERVICE_COMPANY + '_' + cid)
                .cid(cid)
                .token(token);

        return ServiceClient.cloudGet(req).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to get latest version info, result:{}", hr.brief());
                return CompletableFuture.completedFuture(hr);
            }
            int ver = ValParser.getAsInt(hr.data, "ver");
            ServiceInfo si = installed.get(service);
            if(si == null || si.version < ver) {//如果本地版本较新，则不必下载，但是仍然要检查依赖服务
                String digest = ValParser.getAsStr(hr.data, "digest");
                if(StringUtil.isEmpty(digest)) {//无签名，则表示接口响应错误
                    LOG.error("Invalid digest of {}.{}", service, ver);
                    return HandleResult.future(RetCode.DATA_WRONG, "invalid digest");
                }
                ServicePackage sp = new ServicePackage(service, ver, digest, cdnSite, si != null);
                ServicePackage sp1 = packages.get(service);
                if(sp1 == null || sp1.ver < sp.ver) { //已在下载列表，且版本不比它新，则放弃
                    packages.put(service, sp);
                }
            }
            List<Object> list = ValParser.getAsList(hr.data, "dependencies");
            if(list == null || list.isEmpty()) {//无依赖服务
                return HandleResult.future();
            }
            List<DependencyInfo> dependencies = new ArrayList<>();
            for(Object d : list) {
                DependencyInfo di = DependencyInfo.parse(d);
                if(di == null) {
                    LOG.error("Fail to parse dependency of service `{}`", service);
                    return HandleResult.future(RetCode.DATA_WRONG, "invalid dependency");
                }

                si = installed.get(di.name);
                if(si != null && si.version >= di.ver) {
                    continue;
                }

                ServicePackage sp = packages.get(di.name);
                if(sp != null && sp.ver >= di.ver) {
                    continue;
                }
                dependencies.add(di);
            }
            if(dependencies.isEmpty()) { //依赖项都已经安装了
                return CompletableFuture.completedFuture(hr);
            }

            Map<String, HandleResult> results = new ConcurrentHashMap<>();
            List<CompletableFuture<HandleResult>> cfs = new ArrayList<>();
            for(DependencyInfo di : dependencies) { //查询依赖服务的依赖服务，需要递归查询
                cfs.add(requiredServices(cid, token, di.name, evm, installed, packages, cdnSite).whenCompleteAsync((r, e) -> {
                    if(e != null) {
                        LOG.error("Fail to list required packages of {}", di.name, e);
                        results.put(di.name, new HandleResult(RetCode.INTERNAL_ERROR, "fail to get version info of " + di.name));
                    } else {
                        results.put(di.name, r);
                    }
                }, Pool));
            }

            return CompletableFuture.allOf(cfs.toArray(new CompletableFuture<?>[0])).thenComposeAsync(_v -> {
                for(Map.Entry<String, HandleResult> e : results.entrySet()) {
                    if(e.getValue().code != RetCode.OK) { //只要有一个失败，则不可以继续进行下去
                        LOG.error("Fail to get depended service `{}`", e.getKey());
                        return CompletableFuture.completedFuture(e.getValue());
                    }
                }
                return HandleResult.future();
            }, Pool);
        }, Pool);
    }

    /**
     * 删除服务，本质为删除服务的目录，并没有删除对应的数据
     * @param service 服务名
     * @param omPwd om密码
     * @return true为删除成功，false为被依赖，不可删除
     */
    public static CompletableFuture<HandleResult> unInstall(String service, String omPwd) {
        Map<String, ServiceInfo> services = installedServices();
        ServiceInfo si = services.get(service);
        if(si == null) {
            return HandleResult.future();
        }

        //判断服务是否被其他服务依赖
        for(ServiceInfo s : services.values()) {
            if(s.dependencies == null) {
                continue;
            }
            for(ServiceInfo.Dependency dp : s.dependencies) {
                if(dp.name.equals(service)) {
                    return HandleResult.future(RetCode.SERVICE_ERROR, "it was dependent by " + s.name);
                }
            }
        }

        //删除服务目录，服务客户端缓存文件也同时删除，以免再次安装时不更新它
        if(!FileUtil.remove(new File(ServiceInfo.serviceHome(service)))
           || !FileUtil.remove(new File(FileUtil.addPath(AbsPlatform.clientsRoot(), service + ".zip")))) {
            return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to remove");
        }
        
        if(PartitionConfig.instance().mode != DeployMode.SINGLETON) {
            return HandleResult.future();
        }

        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_BIOS)
                .url("/service/remove")
                .cid(CompanyInfo.instance().id)
                .traceId("rmv_" + service)
                .appendPara("service", service)
                .token(SecureUtil.sha256(omPwd));
        return BiosClient.delete(req).exceptionally(e -> {
            LOG.error("Fail to unregister service {}", service, e);
            return HandleResult.InternalError;
        });
    }
    
    /**
     * 升级服务，先卸载(不会删除数据库)，再安装，安装后要重启，只需数据库升级脚本
     * @param service 服务名
     * @param evm 平台运行环境
     */
    public static CompletableFuture<HandleResult> update(String service, String omPwd, String evm) {
        return unInstall(service, omPwd).thenComposeAsync(hr -> {//先卸载
            if(hr.code != RetCode.OK) {
                return CompletableFuture.completedFuture(hr);
            }
            return install(service, evm); //再安装
        }, Pool); 
    }
    
    /**
     * 在bios服务中初始化所有服务的公钥&私钥，并写入本地conf/services.keystore
     * @return 异步结果
     */
    public static CompletableFuture<HandleResult> init() {
        return HandleResult.future();
    }
    
    /**
     * 获取所有已安装服务的信息，直接读取服务的service.cfg文件，
     * 不考虑service是否在运行
     * @return 服务列表
     */
    public static Map<String, ServiceInfo> installedServices() {
        List<String> subs = ServiceInfo.listServices();
        Map<String, ServiceInfo> list = new HashMap<>();
        for(String serviceHome : subs) {
            int pos = serviceHome.lastIndexOf(File.separatorChar);
            String serviceName = serviceHome.substring(pos + 1);
            ServiceInfo si = ServiceInfo.parse(serviceHome, serviceName, null, false);
            if(si == null) {
                LOG.error("Fail to parse service({})'s info", serviceName);
                continue;
            }
            list.put(si.name, si);
        }

        return list;
    }
    
    static class DependencyInfo {
        final int service;
        final int ver;
        final String name;

        private DependencyInfo(int service, int ver, String name) {
            this.service = service;
            this.ver = ver;
            this.name = name;
        }

        public static DependencyInfo parse(Object o) {
            Map<String, Object> m = ValParser.parseObject(o);
            if(m == null || m.isEmpty()) {
                LOG.error("Invalid dependency servcie info `{}`", o);
                return null;
            }
            int service = ValParser.getAsInt(m, "service");
            if(service < 0) {
                LOG.error("Invalid dependency servcie {}", service);
                return null;
            }
            int ver = ValParser.getAsInt(m, "ver");
            if(ver < 0) {
                LOG.error("Invalid dependency servcie version {}", ver);
                return null;
            }

            String name = ValParser.getAsStr(m, "name");
            return new DependencyInfo(service, ver, name);
        }
    }
    
    public static class ServicePackage {
        final String service;
        final int ver;
        final String url;
        final String digest;
        final boolean isUpdate; //待安卓服务是否为升级版本，而不是全新安装

        public ServicePackage(String service, int ver, String digest, String url, boolean isUpdate) {
            this.service = service;
            this.ver = ver;
            this.url = url;
            this.digest = digest;
            this.isUpdate = isUpdate;
        }
    }
}
