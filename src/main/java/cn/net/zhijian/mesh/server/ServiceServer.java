package cn.net.zhijian.mesh.server;

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.CompanyInfo.RunMode;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.HandleTask;
import cn.net.zhijian.mesh.bean.RequestStat;
import cn.net.zhijian.mesh.bean.ServiceReport;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsConnection;
import cn.net.zhijian.mesh.frm.abs.AbsFileMethod;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.abs.AbsTokenChecker;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo.ServiceType;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.frm.method.ApiMethod;
import cn.net.zhijian.mesh.frm.method.BigFileMethod;
import cn.net.zhijian.mesh.frm.method.SmallFileMethod;
import cn.net.zhijian.mesh.frm.service.BuiltinInitializer;
import cn.net.zhijian.mesh.frm.service.backend.BackendBase;
import cn.net.zhijian.mesh.server.TimerKeeper.TimerTaskWrapper;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.DateUtil.PeriodType;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.HttpUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * 1）加载所有service的配置，并更新注册表
 * 2）初始化或升级数据库
 * 安卓版本，在files目录下的目录结构
 * MESH_ROOT-/
 *  mesh_meta.db
 *  services
 *    |_serviceName
 *       |__bin(class, so...)
 *       |__api(API configures)
 *       |__file(static files, for example, templates)
 *           |__放在此目录的，不用认证即可访问，访问的URL无需带/file前缀
 *       |__database.cfg(database/tables define)
 *       |__service.cfg
 *    webdb 服务的数据库都存在webdb服务中 
 *       |__serviceName
 *         |__dbname1
 *         |__dbname2
 * @author flyinmind of csdn.net
 */
public class ServiceServer extends IServiceServer.AbsServiceServer implements IThreadPool  {
    private static final String SERVICE_CFG_EXT = ".cfg";
    private static final String JSONAPI_CFG_EXT = ".json";
    private static final String MACRO_FILE = "macros.def";
    private static final String REQUEST_REC_DB = "requests";
    private static final String REQUEST_REPORT_AT = "reqReportAt"; //上报请求计数给至简网格的时间，24小时上报一次
    private static final String TIMER_STATUS_REPORT = "status_reporter";

    private static final int BLOCK_INTERVAL = 15 * 1000; //milliseconds
    private static final int BILLING_INTERVAL = 600 * 1000;
    private static final int REPORT_INTERVAL = 300 * 1000; //ms，状态上报间隔时间
    private static final long BIGFIIE_SIZE = 64 * 1024; //最小的大文件，超过此值，使用bigfilemethod

    private static final String DEFAULT_INDEX = '/' + IConst.SERVICE_WWW + IConst.INDEX_FILE; 
    private static final Logger LOG = LogUtil.getInstance();
    private static int reportStatsAt = 0; //访问技术上报至简网格的时间，UTC-hour
    private static int saveStatsAt = 0; //访问计数存本地时间，UTC-hour

    private static final Map<String, Balance> billings = new ConcurrentHashMap<>(); //key:service+'_'+cid

    private final Map<String/*url*/, ApiMethod> apiMethods = new ConcurrentHashMap<>();
    private final Map<String/*url*/, AbsFileMethod> fileMethods = new ConcurrentHashMap<>();
    
    private AbsRDBWorker visitStats;
    private boolean started = false;

    /**
     * 加载所有服务的配置信息，并初始化omagent的接口
     * @param workDir 项目运行的根目录
     *  如果有，则用omPwd生成token，从bios主实例获取
     */
    public ServiceServer(String workDir) throws MeshException {
        super(workDir);
    }
    
    private void load(String pwd) throws MeshException {
        apiMethods.clear();
        fileMethods.clear();

        SmallFileMethod.loadContentTypes();
        BackendBase.createApis(this);

        //服务的密钥对都是第一次启动时产生，并记录在bios中
        //多实例环境，在第一次启动实例时需要输入OM密码，从bios中获取服务密钥对，
        //获取后存入本地conf/service.keystore文件，后面启动无需再输入OM密码
        if(!loadResOfAllServices(pwd)) {
            throw new MeshException("Fail to load services from `" + workDir + "`");
        }

        //私有云部署时，需要创建httpdns、company兼容接口
        if(CompanyInfo.instance().isRoot()) {
            LOG.debug("Need not virtual service httpdns and company under ROOT mode");
            //根环境有专门的httpdns、company服务，不需要虚拟服务做兼容
        } else {
            BuiltinInitializer.loadCompatibleApis(this);
        }
    }

    public void printMethods() {
        LOG.info("API num:{}", apiMethods.size());
        LOG.info("FILE num:{}", fileMethods.size());
        if(LOG.isDebugEnabled()) {
            for(ApiMethod am : apiMethods.values()) {
                String visibility = am.apiInfo.isFlagSet(ApiInfo.FLAG_PRIVATE) ? "private" : "public";
                String m = am.apiInfo.method == null ? "*" : am.apiInfo.method;
                LOG.debug("{},{}:{}", visibility, m, am.apiInfo.url);
            }
        }
    }

    /**
     * 加载所有服务信息，遍历services目录下的所有子目录，
     * 如果子目录下存在service.cfg文件，则加载，否则不加载
     * @return 加载成功则返回false，只要有一个加载失败则返回false
     */
    private boolean loadResOfAllServices(String pwd) throws MeshException {
        FileUtil.createDir(AbsPlatform.clientsRoot()); //创建存放客户端侧zip文件的路径
        ServiceInfo bios = services.get(SERVICE_BIOS);
        if(bios != null) {
            //如果bios存在，则需要首先加载bios，因为其他服务依赖bios的server/getKey获取服务密钥对
            //如果bios服务在其他实例，需要保证BIOS服务已经正常启动了
            try {
                HandleResult hr = loadResOfService(bios, pwd).get(5, TimeUnit.SECONDS);
                if(hr.code != RetCode.OK) {
                    LOG.error("Fail to loadResOfService({}),result:{}", SERVICE_BIOS, hr.brief());
                    return false;
                }
            } catch (Exception e) {
                LOG.error("Fail to loadResOfService({})", SERVICE_BIOS, e);
                throw new MeshException("fail to loadResOfService " + SERVICE_BIOS, e);
            }
        }

        List<HandleTask> tasks = new ArrayList<>();
        for (ServiceInfo si : services.values()) {
            if(SERVICE_BIOS.equals(si.name)) {
                continue; //bios加载过了
            }
            // 多线程加载服务的资源，有利于降低加载时间
            tasks.add(new HandleTask(si.name, loadResOfService(si, pwd)));
        }
        
        //检查服务间的依赖关系，如果是在运维平台上操作，所以这里不检查
        return HandleTask.waitHolder("loadResOfService", BLOCK_INTERVAL, tasks);
    }
    
    /**
     * 服务启动入口。
     * 所有服务配置都已加载完毕，且http服务已经启动后，再执行afterload。
     * 任何一个service的afterLoad调用失败，都会返回失败。
     * @param pwd 公司密码或运维totp密码
     * @return 全部执行成功则返回true
     */
    @Override
    public boolean startServices(String pwd) {
        CompanyInfo localCompany = CompanyInfo.instance();
        if(localCompany == null || !localCompany.isValid()) {
            throw new IllegalStateException("Default company not set");
        }

        LOG.info("startServices,mode:{},cid:{}", PartitionConfig.instance().mode.name(), localCompany.id);
        try {
            load(pwd); //load中已调用了每个服务watcher.beforeLoad
        } catch (MeshException e) {
            LOG.error("Fail to load apis of all services", e);
            return false;
        }

        printMethods();

        /*
         * 在安卓环境中，无法依赖用户具有安装复杂应用的能力。
         * 如果需要启动时自动安装服务，则先安装bios、oauth2、webdb、user。
         * base/reg中调用服务的watcher.afterLoad
         * 基础服务优先启动
         */
        Set<String> baseServices = new LinkedHashSet<>(List.of(
                SERVICE_BIOS,
                SERVICE_BACKEND,
                SERVICE_OAUTH2,
                SERVICE_WEBDB,
                SERVICE_KEYSTORE,
                SERVICE_CONFIG,
                SERVICE_SEQID,
                SERVICE_SCHEDULE,
                //根环境中，user服务依赖company查询dbno
                //所以放在user之前，先触发afterload注册到bios中，user在调用时才能找到它
                SERVICE_COMPANY,
                SERVICE_USER,
                SERVICE_WORKFLOW
        ));

        for(String i : baseServices) {
            ServiceInfo si = services.get(i);
            //被依赖服务必须先启动，
            //非单例情况，服务不一定在同一个实例，所以可能为空
            if(si == null) {
                LOG.debug("{} not installed in this instance", i);
                continue;
            }
            if(!initOne(si, pwd)) {
                return false;
            }
        }

        //必须包一层，否则随着startedServices变化，baseServices也会变化
        Set<String> startedServices = new HashSet<>(baseServices);
        int cid = localCompany.id;
        //如果全部在一个实例上运行，第一次没有初始化的情况下，如果依赖的服务尚未注册，
        //则服务启动会失败，需要再次执行启动才能成功
        int num = services.size() - startedServices.size();
        LOG.info("Initialize other {} services, cid:{}", num, cid);
        long maxTime = num * 3000L; //每个最多3秒钟
        long start = System.currentTimeMillis();
        Collection<ServiceInfo> serviceInfos = services.values();

        do {
            Collection<ServiceInfo> leftServiceInfos = new ArrayList<>();
            for (ServiceInfo si : serviceInfos) {
                if (startedServices.contains(si.name) || si.tokenWorker == null) {
                    continue;
                }

                if (!canInit(si, startedServices)) {
                    leftServiceInfos.add(si);
                    continue;
                }

                try {
                    // 逐个执行，而不是并发。使用并发，会出现各式问题，
                    // 比如，在安卓中，ServerMain服务莫名其妙地重启
                    HandleResult hr = si.watcher.afterLoad(this, si, pwd).get(BLOCK_INTERVAL, TimeUnit.SECONDS);
                    if(hr.code != RetCode.OK) {
                        LOG.error("Fail to initialize service {},cid:{},result:{}", si.name, cid, hr.brief());
                        return false;
                    }
                    startedServices.add(si.name);
                    LOG.info("Success to initialize service {},cid:{}", si.name, cid);
                } catch (Exception e) {
                    LOG.error("Fail to init service {},cid:{}", si.name, cid, e);
                }
            }

            serviceInfos = leftServiceInfos;
            LOG.info("{} services not initialized,use {}ms", serviceInfos, System.currentTimeMillis() - start);
        } while (!serviceInfos.isEmpty() // 都注册过了，或者超过n秒仍然不能完成，则结束
                && System.currentTimeMillis() - start < maxTime);

        //调用每个服务watcher.startup，基本服务已经调用过，所以需要跳过
        startupServices(startedServices);

        LOG.debug("Start visitStats");
        this.visitStats = initStatsDb();
        LOG.debug("visitStats started");
        TimerKeeper.addTimerTask(new TimerTaskWrapper(
            TIMER_STATUS_REPORT,
            PeriodType.CYCLE,
            REPORT_INTERVAL, //5分钟
            () -> {
                reportStatus(); //向bios上报每个服务的状态，以便于在服务发现时能够及时更新
                watch(); //调用每个服务的Watcher.watch接口，对服务施加周期性影响
                saveVisitStats(false); //每小时记录一次
                reportVisitStats(false); //每天上报一次
            }
        ));
        TimerKeeper.startTimer(); //启动公用定时器

        started = true;
        return true;
    }

    private AbsRDBWorker initStatsDb() {
        ServiceInfo si = services.get(IConst.SERVICE_BACKEND); //一定存在，不会为空
        if(si == null) {
            throw new MissingResourceException(IConst.SERVICE_BACKEND + " not exists", IConst.SERVICE_BACKEND, "service");
        }
        AbsRDBWorker db = (AbsRDBWorker)si.getLocalDBWorker(IDBConst.RDB, REQUEST_REC_DB);
        if(db == null) {
            throw new MissingResourceException("fail to load local db", IConst.SERVICE_BACKEND, REQUEST_REC_DB);
        }
        //重启时删除一年前的数据
        db.executeRawDML("delete from requests where at<" + ((System.currentTimeMillis() / IConst.HOUR_MS) - 366 * 24));
        reportStatsAt = ValParser.parseInt(db.getSysConfig(REQUEST_REPORT_AT), 0);
        saveStatsAt = (int)(System.currentTimeMillis() / IConst.HOUR_MS);//换成UTC hour
        return db;
    }

    /**
     * 将服务的运行情况报给bios，以便于调用方可以及时调整
     */
    private void reportStatus() {
        LOG.debug("Report service status");
        List<Map<String, Object>> list = new ArrayList<>();
        for(ServiceInfo si : this.services.values()) {
            list.add(Map.of("name", si.name, "ver", si.version));
        }
        if(list.isEmpty()) {
            return;
        }

        String addr = ChannelConfig.instance().localHttpAddr();
        ServiceReqBuilder builder = ServiceClient.backendReqBuilder(SERVICE_BIOS)
            .url("/status/srvreports")
            .traceId("reportStatus")
            .cid(CompanyInfo.instance().id)
            .put("addr", addr)
            .put("partId", PartitionConfig.instance().partition)
            .put("services", list)
            .appToken("*");

        BiosClient.post(builder).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to call reportStatus", e);
                return;
            }
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to reportStatus node@{} to bios,result:{}", addr, hr.brief());
            } else {
                LOG.debug("Success to reportStatus node@{} to bios", addr);
            }
        }, Pool);
    }
    
    private void watch() {
        for(ServiceInfo si : services.values()) {
            try {
                si.watcher.watch(this, si);
            } catch(Exception e) {
                LOG.error("Fail to call {}.report", si.name, e);
            }
        }
    }

    /**
     * 服务端每隔1小时记录一次请求统计
     * @param force 是否强制记录
     */
    private void saveVisitStats(boolean force) {
        int cur = (int)(System.currentTimeMillis() / IConst.HOUR_MS);
        if(!force && cur == saveStatsAt) { //1小时记录一次
            return;
        }
        if(visitStats == null) {
            LOG.error("visitStats hasn't been initialized in saveVisitStats");
            return;
        }
        printMemoryUsage();
        LOG.debug("Save request stats");
        StringBuilder initSqls = new StringBuilder(4096)
            .append("insert or ignore into requests(cid,service,at) values");
        List<String> saveSqls = new ArrayList<>();
        int n = 0;

        for(ServiceInfo si : services.values()) {
            Map<Integer, RequestStat> stats = si.stats();
            for(Map.Entry<Integer, RequestStat> p : stats.entrySet()) {
                RequestStat rs = p.getValue();
                int apis = rs.apis();
                if(apis <= 0) { //无请求，不上报
                    continue;
                }
                int cid = p.getKey();
                if(n > 0) {
                    initSqls.append(',');
                }
                n++;
                initSqls.append("(").append(cid).append(",'")
                    .append(si.name).append("',").append(cur).append(')');
                saveSqls.add("update requests set api=api+" + apis
                    //关机时会强制记录部分数据，所以这里需要加上，而不是直接等于
                    + ",exc=exc+" + rs.exceptions()
                    + ",fail=fail+" + rs.failures()
                    + " where cid=" + cid
                    + " and service='" + si.name
                    + "' and at=" + cur);
            }
        }
        
        if(n > 0) {
            try(AbsConnection conn = visitStats.getWriteConn()) {
                boolean isOk = false;
                try {
                    visitStats.beginTransaction(conn);
                    visitStats.executeRawDML(conn, initSqls.toString());
                    visitStats.executeRawDML(conn, saveSqls);
                    isOk = true;
                    saveStatsAt = cur;
                } catch(Exception e) {
                    LOG.error("Fail to execute save visitStats sqls", e);
                } finally {
                    visitStats.endTransaction(conn, isOk);
                }
            } catch(MeshException se) {
                LOG.error("Fail to open connection of db {}", REQUEST_REC_DB,  se);
            }
        }
    }

    private CompletableFuture<HandleResult> reportVisitStats(boolean force) {
        int cur = (int)(System.currentTimeMillis() / IConst.HOUR_MS);
        if(!force && cur - reportStatsAt < 24) { //24小时上报一次
            return HandleResult.future();
        }
        if(visitStats == null) {
            LOG.error("visitStats hasn't been initialized in reportVisitStats");
            return HandleResult.future(RetCode.INTERNAL_ERROR, "visitStats not initialized");
        }

        visitStats.executeRawDML("delete from requests where at<" + (cur - 366 * 24));
        String sql="select cid,service,at,api,exc,fail from requests where at>"
                + (reportStatsAt - 1) + " and at<" + cur; //不上报当前这个小时的数据，因为可能还未到最后一秒
        List<Object[]> lines = visitStats.queryArrays(sql);
        String from = DateUtil.utcToLocale((long)reportStatsAt * IConst.HOUR_MS);
        if(lines == null || lines.isEmpty()) {
            LOG.debug("There is no stat from {}", from);
            return HandleResult.future();
        }
        LOG.info("There are {} stats from {}/{}", lines.size(), from, reportStatsAt);

        String service;
        int day;
        int cid;
        int at, hour;
        ServiceReport sr;
        String key;
        Map<String, ServiceReport> srs = new HashMap<>();
        StringBuilder json = new StringBuilder(4096).append("{\"stats\":[");
        int n;
        
        for(Object[] line : lines) { //按天合并，可能存在多天的情况，比如关机重启
            n = 0;
            cid = ValParser.parseInt(line[n++], 0);
            service = ValParser.parseString(line[n++]);
            at = ValParser.parseInt(line[n++], 0);
            day = at / 24; //utc天
            hour = at % 24; //一天中的小时
            key = service + '_' + day + '_' + cid;
            sr = srs.get(key);
            if(sr == null) {
                sr = new ServiceReport(cid, service, day);
                srs.put(key, sr);
            }
            sr.apis[hour] += ValParser.parseInt(line[n++], 0); //api
            sr.excs[hour] += ValParser.parseInt(line[n++], 0); //exception
            sr.fails[hour] += ValParser.parseInt(line[n], 0); //fail
        }

        n = 0;
        for(ServiceReport report : srs.values()) {
            if(n > 0) {
                json.append(',');
            }
            json.append("{\"name\":\"").append(report.service)
                .append("\",\"cid\":").append(report.cid)
                .append(",\"day\":").append(report.day)
                .append(",\"apis\":[")
                .append(report.apis[0]);
            for(hour = 1; hour < IConst.DAY_HOURS; hour++) {
                json.append(',').append(report.apis[hour]);
            }
            json.append("],\"fails\":[").append(report.fails[0]);
            for(hour = 1; hour < IConst.DAY_HOURS; hour++) {
                json.append(',').append(report.fails[hour]);
            }
            json.append("],\"excs\":[").append(report.excs[0]);
            for(hour = 1; hour < IConst.DAY_HOURS; hour++) {
                json.append(',').append(report.excs[hour]);
            }
            json.append("]}");
            n++;
        }
        json.append("]}");
        CompanyInfo ci = CompanyInfo.instance();
        ServiceReqBuilder builder = ServiceClient.backendReqBuilder(IConst.SERVICE_APPSTORE)
                .traceId("Rpt_" + ci.id + '_' + reportStatsAt)
                .body(json.toString())
                .cid(ci.id);
        CompletableFuture<HandleResult> cf;
        if(ci.mode == RunMode.ROOT) {
            //访问站内的接口，任何一个站内服务都可以调用此接口
            builder.url("/stats/root_report").appToken("*");
            cf = ServiceClient.servicePost(builder);
        } else {
            AccessToken tk = ci.adminToken(IConst.SERVICE_APPSTORE);
            builder.url("/stats/report").token(tk.generate());
            cf = ServiceClient.cloudPost(builder);
        }
        cf.whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to call /stats/report {}@{}", ci.id, cur, e);
                return;
            }
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to call /stats/report {}@{},result:{}", ci.id, cur, hr.brief());
                return;
            }
            LOG.info("Success to call /stats/report {}@{}", ci.id, cur);
            reportStatsAt = cur;
            visitStats.setSysConfig(REQUEST_REPORT_AT, Integer.toString(cur));
        }, Pool);
        return cf;
    }
    
    private static void printMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        long usedMemory = totalMemory - freeMemory;

        LOG.info("Memory,Used:{},Total:{},Max:{},Free:{}",
                usedMemory, totalMemory, maxMemory, freeMemory);
    }
    
    /**
     * 返回指定服务的访问统计信息
     * @param cid 公司ID
     * @param service 服务名
     * @param from 开始时间（包括），UTC小时
     * @param to 结束时间，UTC小时
     * @return 数据列表，at,api,exc,fail
     */
    @Override
    public List<Object[]> visitStats(int cid, String service, int from, int to) {
        return visitStats.queryArrays(
                "select at,api,exc,fail from requests where cid=" + cid
                + " and service='" + service + "' and at>"
                + (from - 1) + " and at<" + (to + 1)
                + " order by at asc");
    }
    
    /**
     * 增加计费，不管余额是否充足
     * 当上报消费量之后，得知余额不足，则会将其置为无效，此后就不能使用。
     * 如果重启，未上报的计费会消失。因为计费只针对运行在云侧的服务，所以此种缺陷可以容忍。
     * 为了简化设计，尽量由平台承担损失，减少用户损失。
     * @param cid 公司id
     * @param service 服务
     * @return 计费结果，true表示通过
     */
    private Balance getBalance(int cid, ServiceInfo service) {
        String k = service.name + '_' + cid;
        Balance balance = billings.get(k);
        if(balance == null) {
            balance = new Balance(service, cid);
            billings.put(k, balance);
        }
        balance.val.incrementAndGet();
        return balance;
    }
    
    /**
     * 退出时，强制计费一遍
     */
    private CompletableFuture<Void> billingAll() {
        CompletableFuture<HandleResult> cf;
        List<CompletableFuture<HandleResult>> tasks = new ArrayList<>();
        for(Balance b : billings.values()) {
            cf = b.billing(System.currentTimeMillis(), true);
            if(cf != null) {
                tasks.add(cf);
            }
        }
        return CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[]{}));
    }
    
    private boolean initOne(ServiceInfo si, String pwd) {
        try {
            HandleResult hr = si.watcher.afterLoad(this, si, pwd).get(BLOCK_INTERVAL, TimeUnit.SECONDS);
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to call {}.afterLoad,result:{}", si.name, hr.brief());
                return false;
            }
            //基础服务需要提前启动，不是在startupServices中启动
            hr = si.watcher.startup(this, si).get(BLOCK_INTERVAL, TimeUnit.SECONDS);
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to startup service {},result:{}", si.name, hr.brief());
                return false;
            }
            LOG.info("Success to startup service {}", si.name);
            return true;
        } catch (Exception e) {
            LOG.error("Fail to initialize service {}", si.name, e);
            return false;
        }
    }

    /**
     * 启动服务，调用所有服务watcher.startup
     * @param baseServices 基本服务，已经startup过
     */
    private void startupServices(Set<String> baseServices) {
        Map<String, String> kps = new HashMap<>();
        
        for(ServiceInfo si : services.values()) {
            kps.put(si.name, si.tokenWorker.key().toString());
            if(baseServices.contains(si.name)) {
                continue;
            }
            si.watcher.startup(this, si).whenCompleteAsync((hr, e) -> {
                if (e != null) {
                    LOG.error("Fail to call startup of {}", si.name, e);
                } else if (hr.code != RetCode.OK) {
                    LOG.error("Fail to call startup of {},result:{}", si.name, hr.brief());
                } else {
                    LOG.debug("Success to call startup of {}", si.name);
                }
            }, Pool);
        }
        saveKeyPairs(kps);
    }

    
    /**
     * 加载一个服务的资源，包括接口定义与静态文件
     * @param si 服务信息
     * @param pwd 公司密码或om密码
     * @return 成功则返回true
     */
    private CompletableFuture<HandleResult> loadResOfService(ServiceInfo si, String pwd) {
        LOG.debug("load service {} from {}", si.name, si.homeDir);

        return si.watcher.beforeLoad(this, si, pwd).thenComposeAsync(result -> {
            if(result.code != RetCode.OK) {
                LOG.error("Fail to call {}.watcher.beforeLoad, result:{}", si.name, result.brief());
                return CompletableFuture.completedFuture(result);
            }
            LOG.debug("Success to call {}.watcher.beforeLoad", si.name);
            if(si.type == ServiceType.BUILTIN) { //builtin服务，接口都是内部实现的，无需加载
                return CompletableFuture.completedFuture(result);
            }
            CompanyInfo ci = CompanyInfo.instance();
            CompletableFuture<HandleResult> cf;
            if(PartitionConfig.instance().isPrivate()) {//只有私有云才需要下载介绍
                cf = ServiceInfo.getIntroduction(si, ci.id, ci.area());
            } else {
                cf = HandleResult.future();
            }
            //先下载介绍，否则加载api时无法加载introduction接口
            return cf.thenComposeAsync(hr -> {
                try {
                    int apiNum = loadApis(si);
                    if (apiNum < 0) {
                        LOG.error("Fail to load apis of `{}`", si.name);
                        return HandleResult.future(RetCode.INTERNAL_ERROR, "invalid apis config");
                    }
    
                    int fileNum = loadFiles(si);
                    if(fileNum < 0) {
                        LOG.error("Fail to load files of `{}`", si.name);
                        return HandleResult.future(RetCode.INTERNAL_ERROR, "invalid static files");
                    }
    
                    LOG.info("Success to load {} apis and {} files of {} from {}",
                            apiNum, fileNum, si.name, si.homeDir);
                    return HandleResult.future();
                } catch (IOException e) {
                    LOG.error("Fail to load service config", e);
                    return HandleResult.future(RetCode.INTERNAL_ERROR, "exception happened");
                }
            }, Pool);
        }, Pool);
    }

    /**
     * 加载某一个服务的所有api
     * @param si 服务基本信息
     * @return 成功返回true，否则false
     * @throws IOException 文件读异常
     */
    private int loadApis(ServiceInfo si) throws IOException {
        String cfgsHome = FileUtil.addPath(si.homeDir, SERVICE_URL_API);
        File apisPath = new File(cfgsHome);
        LOG.debug("load {} apis from {}", si.name, cfgsHome);

        int count = 0;
        if(apisPath.exists()) {
            count = loadApis(apisPath, si);
            if(count < 0) {
                return count;
            }
        }

        count += BuiltinInitializer.loadAide(si, this);//为服务添加协助接口
        
        return count;
    }
    
    /**
     * 加载api下所有cfg文件中定义的接口，URL中包括/service名称/apis
     * @param apisPath 服务api配置存放路径
     * @param si 服务信息
     * @return 解析成功的接口数，-1表示加载失败
     * @throws IOException 文件读取异常
     */
    private int loadApis(File apisPath, ServiceInfo si) throws IOException {
        File[] cfgFiles = apisPath.listFiles();
        if(cfgFiles == null || cfgFiles.length == 0) {
            return 0;
        }

        int count = 0;
        Map<String, Map<String, Object>> sameAsList = new HashMap<>();
        Map<String, Object> macros;
        File macroFile = new File(FileUtil.addPath(apisPath, MACRO_FILE));
        if(macroFile.exists() && macroFile.length() > 0) {
            macros = JsonUtil.jsonFileToMap(macroFile, true);
        } else {
            macros = new HashMap<>();
        }
        for(File f : cfgFiles) {
            if(f.isDirectory()) {
                continue; //不读取下一层目录
            }
            String path = f.getCanonicalPath(); //某个api配置文件的完整路径
            if(f.length() == 0) {
                LOG.warn("Config file `{}` is empty", path);
                continue;
            }
            LOG.debug("Start to load config file {}", path);
            
            String cls = f.getName().toLowerCase(); //文件名就是'类'
            if(cls.endsWith(JSONAPI_CFG_EXT)) { //静态接口，比如roles之类的配置内容，可以放在一个json文件，也可以多个
                Map<String, Object> data = JsonUtil.jsonFileToMap(f, true);
                if(data == null || data.isEmpty()) {
                    LOG.error("Invalid static json api config file {} in service {} from `{}`", cls, si.name, path);
                    return -1;
                }
                for(Map.Entry<String, Object> oneJson : data.entrySet()) {
                    String apiName = oneJson.getKey();
                    Map<String, Object> o = ValParser.parseObject(oneJson.getValue());
                    if(o == null || o.isEmpty()) {
                        LOG.error("Invalid static json api {} in service {} from `{}`", apiName, si.name, path);
                        return -1;
                    }
                    ApiMethod method = ApiMethod.createStaticMethod(si, apiName, o);
                    addApi(method);
                    count++;
                }
                continue;
            }
            
            if(!cls.endsWith(SERVICE_CFG_EXT)) {
                continue;
            }
            
            List<Object> methodList = JsonUtil.jsonFileToList(f, true);
            if(methodList == null) { //any api parse failed, give up, can't run with illness
                LOG.error("Invalid method config file {}", path);
                return -1;
            }
            cls = cls.substring(0, cls.length() - SERVICE_CFG_EXT.length());
            path = "/" + si.name + "/" + SERVICE_URL_API;
            if(!cls.equals(SERVICE_URL_ROOT)) {
                //apis in root.cfg, without '/root' in the url
                path += "/" + cls;
            }
            for(Object o : methodList) {
                Map<String, Object> mc = ValParser.parseObject(o);
                if(mc == null) { //fail to parse any api, give it up
                    LOG.error("Fail to parse config as a map in file {}", f.getCanonicalPath());
                    return -1;
                }
                
                if(mc.containsKey(ApiMethod.CFG_SAMEAS)) {
                    sameAsList.put(path + '&' + cls, mc);
                    continue; //放在最后再解析，因为sameAs的接口可能还没有解析
                }
                
                //parse api's definition
                ApiMethod method = ApiMethod.parse(si, path, cls, mc, macros);
                if(method == null) {
                    LOG.error("Fail to parse config in {}, {}", f.getCanonicalPath(), mc);
                    return -1;
                }
                if(method.isPrivate()) {
                    if(method.tokenChecker != null && method.tokenChecker.isUserToken()) {
                        si.visible = true;
                    }
                }
                addApi(method);
                count++;
            }
        }

        //最后再处理sameAs的接口
        for(Map.Entry<String, Map<String, Object>> o : sameAsList.entrySet()) {
            Map<String, Object> mc = o.getValue();
            String k = o.getKey();
            int pos = k.indexOf('&');
            String path = k.substring(0, pos);
            String cls = k.substring(pos + 1);
            String name = ValParser.getAsStr(mc, ApiMethod.CFG_NAME);
            String sameAs = ValParser.getAsStr(mc, ApiMethod.CFG_SAMEAS);
            if(StringUtil.isEmpty(name)) {
                LOG.error("Name must be set in api({}), sameAs:{}", path, sameAs);
                return -1;
            }
            
            UrlPathInfo upi = new UrlPathInfo(si.name).push(IConst.SERVICE_URL_API).push(sameAs);
            String url = upi.toString().toLowerCase();
            ApiMethod sameAsMethod = apiMethods.get(url);
            if(sameAsMethod == null) {
                LOG.error("Same as method({}) not exists in api({}/{})", url, path, name);
                return -1;
            }
            ApiMethod method = ApiMethod.parse(name, path, cls, mc, sameAsMethod, macros);
            if(method == null) {
                LOG.error("Fail to parse method({}),api({}/{})", url, path, name);
                return -1;
            }
            addApi(method);
            count++;
        }
        return count;
    }
    
    /**
     * 加载files下的静态文件，URL中携带/service名称/files
     * @param si 服务信息
     * @throws IOException 文件读取异常
     * @return 是否加载成功
     */
    private int loadFiles(ServiceInfo si) throws IOException {
        String sFilesDir = FileUtil.addPath(si.homeDir, SERVICE_FILE_DIR);
        String urlHead = "/" + si.name + "/";
        LOG.debug("load {}'s static files from {}", si.name, sFilesDir);
        int count = 0;
        
        if (new File(sFilesDir).exists()) {
            long latest = loadFiles(si, sFilesDir.length(), urlHead, sFilesDir);
            File zipFile = new File(FileUtil.addPath(AbsPlatform.clientsRoot(), si.name + ".zip"));
            int n = zipFile.exists() ? 1 : 0;
            // 如果无zip文件则生成它，如果存在且更新时间比所有文件都新，也不必生成
            if(n == 0 || latest > zipFile.lastModified()) {
                if((n = si.compressClient(null, zipFile)) < 0) {
                    LOG.error("Fail to compress client files of {}, ignore it", si.name);
                    return -1;
                }
            }

            if(n > 0) {
                // 如果存在端侧的zip文件，则注册端侧下载接口。
                // zip文件有两种生成路径，一种是服务中自带，这样可以自定义，
                // 一种是启动时自动目录压缩file目录
                String contentType = AbsFileMethod.getContentType(FileUtil.getFileExtension(SERVICE_CLIENT_ZIP));
                try {
                    AbsFileMethod fm = new BigFileMethod(si, zipFile, contentType);
                    addFile(urlHead + SERVICE_CLIENT_ZIP, fm);
                    count++;
                } catch(Exception e) {
                    LOG.error("Fail to load {}", zipFile, e);
                }
            }
        }
        
        String faviconUrl = urlHead + FAVICON_FILE;
        if(!fileMethods.containsKey(faviconUrl)) {
            File f = new File(FileUtil.addPath(si.homeDir, SERVICE_FILE_DIR, FAVICON_FILE));
            if(f.exists()) {
                String contentType = AbsFileMethod.getContentType(FileUtil.getFileExtension(IConst.FAVICON_FILE));
                addFile(faviconUrl, new SmallFileMethod(si, f, contentType));
                count++;
            }
        }

        return count;
    }

    private long loadFiles(ServiceInfo si, int rootLen, String urlHead, String path) throws IOException {
        LOG.debug("Load static files from {}", path);
        List<File> files = FileUtil.listFiles(new File(path), EMPTY_STR);
        long latest = 0;
        long modified;
        
        for(File f : files) {
            String sub = f.getCanonicalPath();
            if(f.isDirectory()) {
                modified = loadFiles(si, rootLen, urlHead, sub);
            } else {
                String fileUrl = sub.substring(rootLen).toLowerCase();
                String url = urlHead;
                /*
                 * 如果path中有#，则后面跟着此文件适应的环境，形如 /aaaa/fff#mb.js。
                 * 当前支持浏览器与移动设备两种，移动设备的agent头部都有
                 * HttpUtil.MOBILE_AGENT_HEAD
                 * 在FileMethod中多中环境对应一个url，
                 * 但是内部存储了多个环境不同的内容
                 */
                int pos = fileUrl.indexOf('#');
                String agent = SmallFileMethod.AGENT_MESHCLIENT;
                if(pos > 0) {
                    int dotPos = fileUrl.lastIndexOf('.');
                    agent = fileUrl.substring(pos + 1, dotPos);
                    fileUrl = fileUrl.substring(0, pos) + fileUrl.substring(dotPos);
                }
                String tail = fileUrl.replace("\\", "/");
                url += tail.charAt(0) == '/' ? tail.substring(1) : tail;
    
                String contentType = AbsFileMethod.getContentType(FileUtil.getFileExtension(f.getName()));
                if(f.length() >= BIGFIIE_SIZE) {
                    addFile(url, new BigFileMethod(si, f, contentType));
                } else {
                    AbsFileMethod fm = fileMethods.get(url);
                    if(fm == null) {
                        fm = new SmallFileMethod(si, f, contentType, agent); //默认都是mc类型的
                        addFile(url, fm);
                    } else if(fm instanceof SmallFileMethod) {//针对不同平台的js
                        ((SmallFileMethod)fm).addFile(f, agent);
                    }
                }
                modified = f.lastModified();
            }
            
            if(modified > latest) {
                latest = modified;
            }
        }
        
        return latest;
    }

    @Override
    public void destroy() {
        TimerKeeper.removeTimerTask(TIMER_STATUS_REPORT);
        CountDownLatch holder = new CountDownLatch(2);

        //必须放在watcher.destroy之前，
        //因为如果同时有webdb服务，会在它的watcher中关闭所有数据库连接
        saveVisitStats(true);
        billingAll().whenCompleteAsync((v,e)-> holder.countDown(), Pool);
        reportVisitStats(true).whenCompleteAsync((hr,e)-> holder.countDown(), Pool);
        
        try {
            holder.await(300, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOG.error("Fail to wait billingAll and reportVisitStats", e);
        }

        Set<String> baseServices = new LinkedHashSet<>(); // 基础服务销毁的顺序不能随意改变
        baseServices.add(SERVICE_USER);
        baseServices.add(SERVICE_SEQID);
        baseServices.add(SERVICE_KEYSTORE);
        baseServices.add(SERVICE_OAUTH2);
        // webdb中会销毁所有数据库实例，所以要放在后面销毁
        baseServices.add(SERVICE_WEBDB);
        // bios放在最后，因为要更新mesh_status
        baseServices.add(SERVICE_BIOS);

        /*
         * 必须先调用服务的destroy，再注销所有的服务接口。
         * 因为destroy中可能会调用接口，当所有服务都在一个实例时，
         * 先注销接口，则依赖服务的destroy会失败
         */
        List<HandleTask> tasks = new ArrayList<>();
        for(ServiceInfo si : services.values()) {
            if(baseServices.contains(si.name)) {
                continue;
            }
            tasks.add(new HandleTask(si.name, si.watcher.destroy(this, si)));
            if(si.classLoader instanceof URLClassLoader) {
                try {
                    ((URLClassLoader)si.classLoader).close();
                } catch (IOException e) {
                    LOG.error("Fail to close classLoader of service {}", si.name);
                }
            }
        }
        HandleTask.waitHolder("destroy", BLOCK_INTERVAL, tasks);

        // 基础服务放在最后销毁，因为其他服务销毁时，可能依赖这些服务
        baseServices.remove(SERVICE_BIOS);
        for(String s : baseServices) {
            destroyOne(services.get(s));
        }
        destroyOne(services.get(SERVICE_BIOS)); //如果存在bios，最后清理，保证其他服务都清理完毕

        ServiceInfo.destroyLocalDBWorkers(); //bios数据库也是localdb，所以必须放在最后清理
        LOG.info("Destroy {} apis method", apiMethods.size());
        for(ApiMethod am : apiMethods.values()) {
            am.destroy();
        }
        apiMethods.clear();

        LOG.info("Destroy {} file methods", fileMethods.size());
        for(AbsFileMethod fm : fileMethods.values()) {
            fm.destroy();
        }
        fileMethods.clear();
        AbsProcessor.clearCache(); //如果不清除，重启时仍然使用老的缓存
        AbsTokenChecker.clearCache();
        if(visitStats != null) { //未正常启动时visitStats为空，但是停止时仍然会调用destroy
            try {
                visitStats.close();
            } catch (IOException e) {
                LOG.warn("Fail to close visitStats", e);
            }
        }
        started = false;
    }
    
    private void destroyOne(ServiceInfo si) {
        if(si == null || si.watcher == null) {
            return;
        }

        try {
            HandleResult hr = si.watcher.destroy(this, si).get(10, TimeUnit.SECONDS);
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to call {}.destroy, result:{}", si.name, hr.brief());
            } else {
                LOG.info("Success to call {}.destroy", si.name);
            }
        } catch (Exception e) {
            LOG.error("Fail to call {}.destory", si.name, e);
        }
    }
    
    
    /**
     * 安装服务
     * 除了加载服务基本信息外，还需要执行在bios注册服务、在webdb中初始化数据库
     * @param service 服务名称
     * @param pwd 公司密码或om密码
     * @return 成功则返回true，否则返回false
     */
    @Override
    public CompletableFuture<HandleResult> install(String service, String pwd) {
        return ServiceTool.install(service, PartitionConfig.instance().environment).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to install {}", service, e);
            } else if(hr.code != RetCode.OK) {
                LOG.error("Fail to install {},result:{}", service, hr.brief());
            } else if(started) {
                startServices(pwd); //必须全部重新加载，因为可能有依赖服务
            }
        }, Pool);
    }
    
    @Override
    public CompletableFuture<HandleResult> update(String service, String omPwd) {
        return ServiceTool.update(service, omPwd, PartitionConfig.instance().environment).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to update {}", service, e);
            } else if(hr.code != RetCode.OK) {
                LOG.error("Fail to update {},result:{}", service, hr.brief());
            } else if(started) {
                startServices(null); //必须全部重新加载，因为可能有依赖服务
            }
        }, Pool);
    }
    
    /**
     * 检查服务所依赖的服务是否已经启动
     * @param si 待检查的服务
     * @param startedServices 已经检查过的服务
     * @return 如果所有依赖项都已启动，则返回true
     */
    private boolean canInit(ServiceInfo si, Set<String> startedServices) {
        if(si.dependencies == null) {
            return true;
        }
        
        for(ServiceInfo.Dependency dp : si.dependencies) {
            if(!startedServices.contains(dp.name)) {
                ServiceInfo dsi = services.get(dp.name);
                LOG.debug("{} not started,{} depends on it", dp.name, si.name);
                if(dsi == null) {
                    //本地没有，但是已在bios注册，则可依赖
                    return isServiceRegistered(si, dp.name);
                }
                return false;
            }
        }
        return true;
    }

    /**
     * 通过判断bios中是否有相应的service目录，来确定服务是否已经注册过
     * @param caller 调用方
     * @param service 待检查的服务名称
     * @return 如果已经注册，则返回true
     */
    private boolean isServiceRegistered(ServiceInfo caller, String service) {
        ServiceReqBuilder req = new ServiceReqBuilder(caller, SERVICE_BIOS)
                .url("/service/registered")
                .cid(CompanyInfo.instance().id)
                .appToken("*")
                .traceId(caller.name)
                .appendPara("service", service);
        try {
            HandleResult hr = BiosClient.get(req).get(5, TimeUnit.SECONDS);
            return hr.code == RetCode.OK;
        } catch (Exception e) {
            LOG.error("Fail to call registered", e);
            return false;
        }
    }

    /**
     * 卸载服务
     * 从内存中删除服务信息，如果removeDb，还需要在webdb中删除数据库
     * @param service 服务名
     * @param omPwd om密码
     */
    @Override
    public CompletableFuture<HandleResult> unInstall(String service, String omPwd) {
        return ServiceTool.unInstall(service, omPwd).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to unInstall {},result:{}", service, hr.brief());
                return CompletableFuture.completedFuture(hr);
            }

            ServiceInfo si = null;
            if(started) {
                //没有使用startServices，它会导致业务中断，以下操作不会
                String sn = '/' + service + '/';
                List<String> rmvs = new ArrayList<>();
                for (String n : apiMethods.keySet()) {
                    if (n.startsWith(sn)) {
                        rmvs.add(n);
                    }
                }

                for (String n : rmvs) {
                    apiMethods.remove(n);
                }

                rmvs.clear();
                for (String n : fileMethods.keySet()) {
                    if (n.startsWith(sn)) {
                        rmvs.add(n);
                    }
                }

                for (String n : rmvs) {
                    fileMethods.remove(n);
                }
                si = services.get(service);
            }
            if(si == null) { //刚安装，还没加载完成时卸载，或未启动时
                LOG.debug("Service {} has not loaded", service);
                return HandleResult.future();
            }
            return si.watcher.destroy(this, si);
        }, Pool).thenApplyAsync(result -> {
            if(result.code != RetCode.OK) {
                LOG.error("Fail to call {}.destroy when unInstall, result:{}", service, result.brief());
            } else {
                LOG.info("Success to call {}.destroy when unInstall", service);
            }            
            services.remove(service); //无论成功与否，都删除
            return result;
        }, Pool);
    }
    
    @Override
    public List<Map<String, String>> apis(String service) {
        List<Map<String, String>> apis = new ArrayList<>();
        for(ApiMethod am : apiMethods.values()) {
            if(am.serviceInfo.name.equals(service)) {
                apis.add(am.toMap());
            }
        }
        return apis;
    }
    
    @Override
    public List<String> files(String service) {
        List<String> files = new ArrayList<>();
        String url;

        for(Map.Entry<String, AbsFileMethod> e : fileMethods.entrySet()) {
            url = e.getKey();
            if(e.getValue().serviceInfo.name.equals(service)) {
                files.add(url);
            }
        }
        return files;
    }

    /**
     * 请求某个服务下的文件
     * @param req 请求体
     */
    @Override
    public void fileExecute(AbsServerRequest req) {
        AbsServerResponse resp = req.createResponse();
        AbsFileMethod fm = fileMethods.get(req.uri);
        if (fm == null) {
            if(StringUtil.isEmpty(req.uri) || req.uri.equals("/")) {
                fm = fileMethods.get(DEFAULT_INDEX);
            } else {
                //动态文件url，包含URL_DYNAMIC_FLAG，参数在uri中
                //比如从appstore下载版本的连接中就包含了URL_DYNAMIC_FLAG
                //文件的路径由URL_DYNAMIC_FLAG后面的内容决定
                int pos = req.uri.indexOf(URL_DYNAMIC_FLAG);
                if(pos > 0) {
                    fm = fileMethods.get(req.uri.substring(0, pos + URL_DYNAMIC_FLAG.length()));
                }
            }
        }
        if (fm == null) {
            resp.error(HttpResponseStatus.NOT_FOUND, RetCode.API_NOT_FOUND);
            if(LOG.isDebugEnabled()) {
                LOG.debug("`{}` not exists", req.uri);
            }
            return;
        }
        req.setServiceInfo(fm.serviceInfo);
        resp.setServiceInfo(fm.serviceInfo);
        fm.response(req, resp);
    }

    /**
     * 处理api请求
     * @param req 请求
     */
    @Override
    public void apiExecute(AbsServerRequest req) {
        AbsServerResponse resp = req.createResponse();
        //默认json格式响应。在apiExecute之前，不可以调用resp.end
        //如果调用，在浏览器中接收不到响应内容
        resp.putHeader(HttpUtil.HEAD_CONTENT_TYPE, HttpUtil.CONTENT_TYPE_JSON);
        ApiMethod am = apiMethods.get(req.uri);
        if(am == null) {
            resp.end(HandleResult.ApiNotFound);
            if(LOG.isDebugEnabled()) {
                LOG.debug("Api {}.{} not exists", req.method, req.uri);
            }
            return;
        }
        req.setServiceInfo(am.serviceInfo);
        resp.setServiceInfo(am.serviceInfo);
        try {
            RequestStat rs = req.getStat();
            rs.incApis(1);
            if(!am.isValidMethod(req.method)) {
                resp.end(HandleResult.ApiNotFound);
                LOG.warn("Invalid method {}.{}", req.method, req.uri);
                rs.incFailures(1);
                return;
            }
            
            //不占用netty线程解析，另外一个好处是当解析错误时，客户端可以得到默认响应
            //缺点是post/put方法在此之前看不到消息体中的请求参数
            if(!req.parseJsonBody()) {
                resp.end(HandleResult.WrongJsonBody);
                LOG.warn("Invalid method {}.{}", req.method, req.uri);
                rs.incFailures(1);
                return;
            }

            /* 
             * 公司类型的服务运行在根环境，需要计费，
             * public接口、或在私有云中，不计费
             */
            if(CompanyInfo.instance().mode == RunMode.ROOT) {
                int cid = req.cid();
                if(am.serviceInfo.type == ServiceType.COMPANY && cid > IConst.ROOT_COMPANY_ID) {
                    Balance balance = getBalance(cid, am.serviceInfo);
                    if(!balance.valid) {
                        LOG.warn("Fail to bill {}.{}", am.serviceInfo.name, cid);
                        resp.end(new HandleResult(RetCode.PAST_DUE));
                        return;
                    }
                    balance.billing(req.reqTime, false);
                }
            }
            am.execute(req, resp);
        } catch (Exception e) {
            resp.end(am.onException);
            LOG.error("Fail to execute {}, times:{}", req.uri, req.getStat().incExceptions(1), e);
        }
    }
    
    @Override
    public void addFile(String url, AbsFileMethod fm) {
        fileMethods.put(url, fm);
    }

    @Override
    public void addApi(ApiMethod am) {
        String url = am.apiInfo.url.toLowerCase();
        int pos = url.lastIndexOf('/');
        //接口名称前面有'__'，都是初始化接口，在Watcher.startUp中会调用它们
        //此类接口都是做初始化工作，比如创建默认工作流，不能在服务中调用它们
        if(url.startsWith(INIT_METHOD_HEAD, pos + 1)) {
            am.serviceInfo.addInitApi(am);
        } else {
            apiMethods.put(url, am);
        }
    }
    
    @Override
    public ApiMethod getApi(String url) {
        return apiMethods.get(url.toLowerCase());
    }
    
    @Override
    public AbsFileMethod getFile(String url) {
        return fileMethods.get(url.toLowerCase());
    }

    private static class Balance {
        final ServiceInfo si;
        final int cid;

        final AtomicInteger val = new AtomicInteger(0); //当前用量
        long nextBillAt = System.currentTimeMillis(); //下次计费时间
        volatile boolean valid = true;
        
        Balance(ServiceInfo si, int cid) {
            this.si = si;
            this.cid = cid;
        }
        
        private CompletableFuture<HandleResult> billing(long cur, boolean force) {
            if(!force && cur < this.nextBillAt) {
                return null;
            }
            this.nextBillAt = System.currentTimeMillis() + BILLING_INTERVAL;
            int v = this.val.get();
            if(v == 0 && this.valid) {
                return null;//计费已失效时，即使用量为0也需上报，因为此期间可能充值了，上报后获得当前的新余量
            }
            
            ServiceReqBuilder req = new ServiceReqBuilder(si, SERVICE_COMPANY)
                    .url("/service/billing")
                    .traceId(this.si.name)
                    .cid(this.cid)
                    .appToken("*")
                    .put("service", this.si.name)
                    .put("cid", this.cid)
                    .put("val", v);
            return ServiceClient.servicePost(req).whenCompleteAsync((hr, e) -> {
                if(e != null) {
                    LOG.error("Fail to bill {}.{}", si.name, cid, e);
                    return;
                }
                
                if(hr.code != RetCode.OK) {
                    LOG.error("Fail to bill {}.{}, {}", si.name, cid, hr.brief());
                    this.valid = hr.code != RetCode.NOT_EXISTS;
                } else {
                    long left = ValParser.getAsLong(hr.data, "balance");
                    this.valid = left > 0;//会有一些损失，但是最长损失10分钟用量
                    this.val.addAndGet(-v); //即使无余量，也要减去已上报的量
                }
            }, Pool);
        }
    }
}