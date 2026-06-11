package cn.net.zhijian.mesh.frm.service.company;

import java.io.File;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.dbworker.SqliteWorker;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.tokenchecker.TokenCheckers;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.IPUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 接受端侧命令，管理服务器
 * 需要COMPANY token检查，root服务侧本地可以生成此类token，
 * 如果在端侧，需要输入公司密码，通过公司密码换取公司token，
 * 这种token的signType为LOCAL_COMPANY_KEY而不是COMPANY_KEY
 * `/company/api/command`
 * @author flyinmind of csdn.net
 *
 */
public class Command extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    private static final Map<String, Function<RequestInfo, CompletableFuture<HandleResult>>> commands = new HashMap<>();
    private final IServiceServer server;

    public Command(IServiceServer server, ServiceInfo serviceInfo, ApiInfo apiInfo) {
        super(serviceInfo, apiInfo, "execute_commands");
        this.server = server;
        commands.put("query", this::query); //查询公司相关的信息
        commands.put("uninstall", this::unInstall); //卸载服务
        commands.put("install", this::install); //安装服务
        commands.put("update", this::update); //更新服务
        commands.put("backup", this::backup); //数据立即备份
        commands.put("restore", this::restore); //数据恢复
        commands.put("setbackup", this::setBackup); //设置备份时间点
        commands.put("setloglevel", this::setLogLevel); //设置日志级别
        commands.put("servicever", this::serviceVer); //服务版本
        commands.put("resettokenpwd", this::resetTokenPwd); //重置远程临时接入码
        commands.put("resetaccesscode", this::resetAccessCode); //重置接入码
        commands.put("saveaccesscode", this::saveAccessCode); //保存接入码
        commands.put("setoutsideaddr", this::setOutsideAddr); //设置公司外网地址
        commands.put("setinfo", this::setInfo); //设置公司相关的信息
        commands.put("changepwd", this::changePassword); //修改公司密码
        commands.put("localservices", this::localServices); //已安装的服务列表
        commands.put("visitstats", this::visitStats); //服务的访问统计信息
        commands.put("functions", this::functions); //支持的功能列表
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        int cid = req.cid();
        CompanyInfo localCompany = CompanyInfo.instance();
        
        if(localCompany.id != cid) {//只能对本地公司执行命令
            return futureResult(RetCode.NO_RIGHT, "invalid company id " + cid);
        }

        String cmd = req.getString("cmd").toLowerCase();
        Function<RequestInfo, CompletableFuture<HandleResult>> f = commands.get(cmd);
        if (f == null) {
            return futureResult(RetCode.WRONG_PARAMETER, "invalid command " + cmd);
        }
        RequestInfo ri = new RequestInfo(req, localCompany);
        return f.apply(ri);
    }
    
    private CompletableFuture<HandleResult> query(RequestInfo req) {
        CompanyInfo ci = req.ci;
        Map<String, Object> data = new HashMap<>();
        data.put("logLevel", LogUtil.getRootLevel());
        data.put("logPath", LogUtil.getLogPath());
        data.put("accessCode", ci.accessCode());
        data.put("companyId", ci.id);
        data.put("creditCode", ci.creditCode);
        data.put("companyName", ci.name());
        data.put("country", ci.country());
        data.put("province", ci.province());
        data.put("city", ci.city());
        data.put("county", ci.county());
        data.put("info", ci.info());
        String addr = ci.outsideAddr();
        if(addr != null) {
            data.put("outsideAddr", IPUtil.takeOffPort(addr));
            data.put("outsideAddr1", addr); //携带了端口号
        } else {
            data.put("outsideAddr", EMPTY_STR);
            data.put("outsideAddr1", EMPTY_STR);
        }
        data.put("insideAddr", IPUtil.takeOffPort(ci.insideAddr));
        data.put("insideAddr1", ci.insideAddr);
        List<InetAddress> ips = IPUtil.getIPList();
        List<String> addrs = new ArrayList<>();
        for(InetAddress ip : ips) {
            if(IPUtil.isLan(ip)) {
                continue;
            }
            addrs.add(ip.getHostAddress());
        }
        data.put("externAddrs", addrs);//服务器拥有的外部IP列表
        data.put("mode", ci.mode.name());
        return futureResult(data);
    }
    
    private CompletableFuture<HandleResult> unInstall(RequestInfo req) {
        String service = req.req.getString("service");
        String pwd = req.req.getString("pwd");
        return server.unInstall(service, pwd);
    }
    
    private CompletableFuture<HandleResult> install(RequestInfo req) {
        String service = req.req.getString("service");
        String pwd = req.req.getString("pwd");
        return server.install(service, pwd);
    }
    
    private CompletableFuture<HandleResult> update(RequestInfo req) {
        String service = req.req.getString("service");
        String pwd = req.req.getString("pwd");
        return server.update(service, pwd);
    }
    
    private CompletableFuture<HandleResult> serviceVer(RequestInfo req) {
        String service = req.req.getString("service");
        ServiceInfo si = server.getService(service);
        HandleResult hr;
        if(si == null) {
            hr = new HandleResult(RetCode.OK, MapBuilder.of("version", 0));
        } else {
            hr = new HandleResult(RetCode.OK, MapBuilder.of("version", si.version));
        }
        
        return CompletableFuture.completedFuture(hr);
    }
    
    /**
     * 获得agent能够支持的特性列表
     * @param req 请求体
     * @return 异步结果
     */
    private CompletableFuture<HandleResult> functions(RequestInfo req) {
        return futureResult(MapBuilder.of("functions", commands.keySet()));
    }

    //强制backup一次
    private CompletableFuture<HandleResult> backup(RequestInfo req) {
        ServiceInfo webdb = server.getService(IConst.SERVICE_WEBDB);
        if(webdb == null) {
            return futureResult(RetCode.INVALID_NODE, "there is no webdb service");
        }
        SqliteWorker.backup(webdb, true).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to backup", e);
            } else {
                LOG.info("Result of backup:{}", hr);
            }
        }, Pool);
        return futureResult(RetCode.OK); //只是表示命令发到了
    }

    private CompletableFuture<HandleResult> restore(RequestInfo req) {
        ServiceInfo webdb = server.getService(IConst.SERVICE_WEBDB);
        if(webdb == null) {
            return futureResult(RetCode.INVALID_NODE, "there is no webdb service");
        }
        SqliteWorker.restore(webdb, AbsPlatform.dbRoot(), req.ci.id).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to restore", e);
            } else {
                LOG.info("Result of restore:{}", hr);
            }
        }, Pool);
        return futureResult(RetCode.OK); //只是表示命令发到了
    }

    private CompletableFuture<HandleResult> setBackup(RequestInfo req) {
        int at = req.req.getInt("at");
        LOG.debug("Set db backupAt {},cid:{}", at, req.ci.id);
        return req.ci.setBackupAt(at).thenApply(hr -> {
            if(hr.code != RetCode.OK) {
                return hr;
            }
            ServiceInfo webdb = server.getService(IConst.SERVICE_WEBDB);
            if(webdb != null) { //私有云环境，当前实例如有webdb服务，则启动它的备份定时任务
                SqliteWorker.updateBackupInfo("" + req.ci.id);
            }
            return hr;
        });
    }

    private CompletableFuture<HandleResult> setLogLevel(RequestInfo req) {
        String level = req.req.getString("level").toUpperCase();
        if(StringUtil.isEmpty(level)) {
            level = "DEBUG";
        }
        
        LogUtil.setRootLevel(level);
        return futureResult();
    }

    private CompletableFuture<HandleResult> setOutsideAddr(RequestInfo req) {
        String addr = req.req.getString("addr"); //可以为空，为空表示清除,也可以有多个，用逗号分隔
        return req.ci.saveToHttpdns(null, addr, null).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to setOutsideAddr to httpdns,result:{}", hr.brief());
            }
            return hr;
        }, Pool);
    }
    
    /**
     * 设置backend访问的接入令牌，只能重置，不能查询，所以一进入，界面上不会显示
     * @param req 请求体
     * @return 异步结果
     */
    private CompletableFuture<HandleResult> resetTokenPwd(RequestInfo req) {
        String token = StringUtil.genRandomCode(10);
        return TokenCheckers.Backend.setTokenPwd(req.ci, token).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.warn("Fail to call resetTokenPwd, result:{}", hr.brief());
                return hr;
            }
            return new HandleResult(MapBuilder.of("token", token));
        }, Pool);
    }

    private CompletableFuture<HandleResult> resetAccessCode(RequestInfo req) {
        String code = StringUtil.genRandomCode(8);
        return saveAccessCodeToHttpDns(req, code);
    }

    private CompletableFuture<HandleResult> saveAccessCode(RequestInfo req) {
        String code = req.req.getString("code");
        return saveAccessCodeToHttpDns(req, code);
    }
    
    private CompletableFuture<HandleResult> saveAccessCodeToHttpDns(RequestInfo req, String code) {
        return req.ci.saveToHttpdns(null, null, code).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to saveAccessCodeToHttpDns to httpdns,result:{}", hr.brief());
                return hr;
            }
            return new HandleResult(RetCode.OK, MapBuilder.of("code", code));
        }, Pool);
    }

    private CompletableFuture<HandleResult> setInfo(RequestInfo req) {
        Map<String, Object> params = req.req.params();
        return req.ci.setInfo(
            ValParser.getAsStr(params, "name", null),
            ValParser.getAsStr(params, "country", null),
            ValParser.getAsStr(params, "province", null),
            ValParser.getAsStr(params, "city", null),
            ValParser.getAsStr(params, "county", null),
            ValParser.getAsStr(params, "info", null)
        );
    }

    private CompletableFuture<HandleResult> changePassword(RequestInfo req) {
        Map<String, Object> params = req.req.params();
        return req.ci.changePwd(
            ValParser.getAsStr(params, "oldPwd"),
            ValParser.getAsStr(params, "newPwd")
        );
    }
    
    private CompletableFuture<HandleResult> localServices(RequestInfo req) {
        //私有云环境获取已安装服务列表
        CompanyInfo ci = req.ci;
        Map<String, ServiceInfo> services = server.services();
        List<String> sl = new ArrayList<>(services.keySet());
        ServiceReqBuilder reqBuilder = ServiceClient.backendReqBuilder(IConst.SERVICE_APPSTORE)
                .url("/api/service/getVersions")
                .traceId(IConst.SERVICE_APPSTORE + '_' + ci.id)
                .cid(ci.id)
                .body("{\"services\":" + JsonUtil.objToJson(sl) + '}');
        //先查询所有服务的版本号，用于决定是否显示更新按钮
        //即使查询失败，也会放回结果，只是更新状态会不准确
        return ServiceClient.cloudPost(reqBuilder).thenApplyAsync(hr -> {
            Map<String, Integer> versions = new HashMap<>();
            if(hr.code == RetCode.OK && hr.data != null) {
                List<Object> vl = ValParser.getAsList(hr.data, "vers");
                if (vl != null) {
                    for (Object v : vl) { //[[service,version],...]
                        List<Object> l = ValParser.parseList(v);
                        versions.put(ValParser.parseString(l.get(0)), ValParser.parseInt(l.get(1), 0));
                    }
                }
            }
            return formServicesResult(services, versions);
        }, Pool).exceptionally(e -> formServicesResult(services, new HashMap<>()));
    }

    private HandleResult formServicesResult(Map<String, ServiceInfo> services, Map<String, Integer> versions) {
        List<Map<String, Object>> list = new ArrayList<>();
        for(Map.Entry<String, ServiceInfo> s : services.entrySet()) {
            ServiceInfo si = s.getValue();
            if(si == null) {
                continue; //目录下没有有效的service.cfg
            }
            Map<String, Object> one = new HashMap<>();
            one.put("name", si.name);
            one.put("displayName", si.displayName);
            one.put("level", si.level);
            one.put("author", si.author);
            one.put("version", si.version());
            int srvVer = ValParser.parseInt(versions.get(si.name), 0);
            one.put("updatable", srvVer > si.version);
            one.put("srvVer", StringUtil.intToVer(srvVer));

            File favicon = new File(FileUtil.addPath(si.homeDir, IConst.SERVICE_FILE_DIR, IConst.FAVICON_FILE));
            if(favicon.exists()) {
                one.put("icon", "/" + si.name + "/" + IConst.FAVICON_FILE);
            } else {
                one.put("icon", "/" + IConst.SERVICE_ASSETS + "/" + IConst.FAVICON_FILE);
            }
            list.add(one);
        }
        return new HandleResult(RetCode.OK, MapBuilder.of("services", list));
    }
    
    private CompletableFuture<HandleResult> visitStats(RequestInfo req) {
        int from = req.req.getInt("from");
        int to = req.req.getInt("to");
        String service = req.req.getString("service");
        if(from <= 0 || to <= 0 || from >= to || StringUtil.isEmpty(service)) {
            LOG.debug("from:{},to:{},service:{}", from, to, service);
            return futureResult(RetCode.WRONG_PARAMETER, "invalid from,to or service");
        }

        List<Object[]> stats = server.visitStats(req.ci.id, service, from, to);
        String[] cols = new String[] {"at", "api", "exc", "fail", "file"};
        return futureResult(MapBuilder.of("stats", stats, "cols", cols));
    }
    
    private static class RequestInfo {
        final AbsServerRequest req;
        final CompanyInfo ci;
        
        RequestInfo(AbsServerRequest req, CompanyInfo ci) {
            this.req = req;
            this.ci = ci;
        }
    }
}
