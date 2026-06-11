package cn.net.zhijian.mesh.builtin.webdb;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.dbworker.SearchDBWorker;
import cn.net.zhijian.mesh.dbworker.SqliteWorker;
import cn.net.zhijian.mesh.dbworker.TreeDBWorker;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsSearchDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig.DeployMode;
import cn.net.zhijian.mesh.frm.config.para.ParameterInfo;
import cn.net.zhijian.mesh.frm.config.tokenchecker.TokenCheckers;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IServiceWatcher.DefaultServiceWatcher;
import cn.net.zhijian.mesh.frm.method.ApiMethod;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * WEBDB服务看护类
 * 包括节点注册、状态上报、销毁等
 * @author flyinmind of csdn.net
 *
 */
public final class Watcher extends DefaultServiceWatcher implements IDBConst {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String SEG_DBS = "dbs";

    private RunConfig runCfg;

    @Override
    public CompletableFuture<HandleResult> beforeLoad(IServiceServer server, ServiceInfo si, String pwd) {
        return innerBeforeLoad(server, si, pwd).whenCompleteAsync((hr, e) -> {
            if(e != null || hr.code != RetCode.OK) {
                return;
            }
            final String nameRegular = "^[a-zA-Z0-9_]{1,30}$";
            //主从节点之间同步数据，只接受其他bios节点的调用
            //如果需要实现数据同步，必须提供/sync接口，并且参数只能是service/db/sqls，
            //这是在sqliteworker中预设的
            String procName = "sync";
            UrlPathInfo url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
            ApiInfo apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_POST, url, null, true);
            Sync sync = new Sync(si, apiInfo, procName);
            RequestInfo requestInfo = new RequestInfo(new ParameterInfo[] {
                new ParameterInfo.Builder("service", ParameterInfo.TYPE_STRING).setRegular(nameRegular).build(),
                new ParameterInfo.Builder("db", ParameterInfo.TYPE_STRING).setRegular(nameRegular).build(),
                new ParameterInfo.Builder("sqls", ParameterInfo.TYPE_STRING).build()
            });
            ApiMethod am = ApiMethod.generate(sync, apiInfo, requestInfo, TokenCheckers.App, null);
            server.addApi(am);

            //检查数据库状态
            procName = "checkup";
            url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
            apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, false);
            AbsProcessor checkup = new AbsProcessor(si, apiInfo, procName) {
                @Override
                protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
                    String service = req.getString(DB_REQ_SERVICE);
                    String db = req.getString(DB_REQ_DB);

                    AbsRDBWorker worker = AbsRDBWorker.instance(req.cid(), service, db);
                    if(worker == null) {
                        //一个db，可当做一个service
                        return futureResult(RetCode.SERVICE_NOT_FOUND, "db `" + service + '.' + db + "` not found");
                    }
                    if(worker.normal()) {
                        return futureResult();
                    }
                    return futureResult(RetCode.INTERNAL_ERROR, "db `" + service + '.' + db + "` abnormal");
                }
            };
            requestInfo = new RequestInfo(new ParameterInfo[] {
                new ParameterInfo.Builder("service", ParameterInfo.TYPE_STRING).setRegular(nameRegular).build(),
                new ParameterInfo.Builder("db", ParameterInfo.TYPE_STRING).setRegular(nameRegular).build()
            });
            am = ApiMethod.generate(checkup, apiInfo, requestInfo, null, null);
            server.addApi(am);
            
            //初始化数据库
            procName = "inittables";
            url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push("om").push(procName);
            apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_POST, url, null, true);
            InitTables initTabs = new InitTables(si, apiInfo, procName);
            requestInfo = new RequestInfo(new ParameterInfo[] {
                new ParameterInfo.Builder("dbNo", ParameterInfo.TYPE_INT, false).setMin(0).setDefault(0).build(), //数据库实例id
                new ParameterInfo.Builder("db", ParameterInfo.TYPE_STRING).setRegular(nameRegular).build(),
                new ParameterInfo.Builder("type", ParameterInfo.TYPE_STRING).setOptions(List.of("rdb", "sdb", "tdb")).build(), //数据库类型
                new ParameterInfo.Builder("version", ParameterInfo.TYPE_STRING, false).setRegular("(\\d+\\.){2}\\d+").build(), //版本号
                new ParameterInfo.Builder("tabledef", ParameterInfo.TYPE_STRING).setMax(300000).build() //表定义
            });
            am = ApiMethod.generate(initTabs, apiInfo, requestInfo, TokenCheckers.AppAll, null);
            server.addApi(am);

            //查询数据库版本
            procName = "ver";
            url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push("om").push(procName);
            apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, true);
            AbsProcessor dbVer = new AbsProcessor(si, apiInfo, procName) {
                @Override
                protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
                    String service = req.getString(DB_REQ_SERVICE);
                    String db = req.getString(DB_REQ_DB);
                    AbsRDBWorker worker = AbsRDBWorker.instance(req.cid(), service, db);
                    if(worker != null) {
                        resp.put("ver", worker.version());
                        return futureResult(resp);
                    }
                    LOG.debug("Fail to get db instance of {}.{}", service, db);
                    return futureResult(RetCode.NOT_EXISTS, "fail to get db instance");
                }
            };
            requestInfo = new RequestInfo(new ParameterInfo[] {
                new ParameterInfo.Builder("service", ParameterInfo.TYPE_STRING).setRegular(nameRegular).build(),
                new ParameterInfo.Builder("db", ParameterInfo.TYPE_STRING).setRegular(nameRegular).build()
            });
            am = ApiMethod.generate(dbVer, apiInfo, requestInfo, TokenCheckers.Mnt, null);
            server.addApi(am);
            
            //设置数据库读写开关
            procName = "setwritable";
            url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push("om").push(procName);
            apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_PUT, url, null, true);
            AbsProcessor setWritable = new AbsProcessor(si, apiInfo, procName) {
                @Override
                protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
                    String service = req.getString(DB_REQ_SERVICE);
                    String db = req.getString(DB_REQ_DB);
                    boolean val = req.getBool("writable");
                    AbsRDBWorker worker = AbsRDBWorker.instance(req.cid(), service, db);
                    if(worker == null) {
                        return futureResult(RetCode.NOT_EXISTS, "fail to get db worker");
                    }
                    worker.setWritable(val);
                    
                    return futureResult();
                }
            };
            requestInfo = new RequestInfo(new ParameterInfo[] {
                new ParameterInfo.Builder("service", ParameterInfo.TYPE_STRING).setRegular(nameRegular).build(),
                new ParameterInfo.Builder("db", ParameterInfo.TYPE_STRING).setRegular(nameRegular).build(),
                new ParameterInfo.Builder("writable", ParameterInfo.TYPE_BOOL).build()
            });
            am = ApiMethod.generate(setWritable, apiInfo, requestInfo, TokenCheckers.Mnt, null);
            server.addApi(am);
            
            //删除数据库
            procName = "remove";
            url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push("om").push(procName);
            apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_DELETE, url, null, true);
            AbsProcessor removeDb = new AbsProcessor(si, apiInfo, procName) {
                @Override
                protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
                    String service = req.getString(DB_REQ_SERVICE);
                    String db = req.getString(DB_REQ_DB);
                    String type = req.getString("type");
                    boolean ok = false;
                    if(RDB.equals(type)) {
                        ok = SqliteWorker.removeInstance(req.cid(), service, db);
                    } else if(SEARCHDB.equals(type)) {
                        ok = SearchDBWorker.removeInstance(req.cid(), service, db);
                    } else if(TREEDB.equals(type)) {
                        ok = TreeDBWorker.removeInstance(req.cid(), service, db);
                    }
                    if(ok) {
                        return futureResult();
                    }
                    return futureResult(RetCode.DB_ERROR, "fail to remove");
                }
            };
            requestInfo = new RequestInfo(new ParameterInfo[] {
                new ParameterInfo.Builder("service", ParameterInfo.TYPE_STRING).setRegular(nameRegular).build(),
                new ParameterInfo.Builder("db", ParameterInfo.TYPE_STRING).setRegular(nameRegular).build(),
                new ParameterInfo.Builder("type", ParameterInfo.TYPE_STRING).build()
            });
            am = ApiMethod.generate(removeDb, apiInfo, requestInfo, TokenCheckers.Mnt, null);
            server.addApi(am);
        }, Pool);
    }

    private CompletableFuture<HandleResult> innerBeforeLoad(IServiceServer server, ServiceInfo si, String pwd) {
        return super.beforeLoad(server, si, pwd).thenComposeAsync(hr -> {
            File cfgFile = RunConfig.configFile(si.homeDir);
            if(cfgFile.exists()) {
                if((runCfg = RunConfig.parse(cfgFile)) != null) {
                    DBHandlerBase.setRunConfig(runCfg);
                    return HandleResult.future();
                }
            }
            
            //因为数据库需要非常谨慎的处理，所以如果配置发生了变更，则必须重启实例来更新配置
            return getConfig(si).thenApply(rc -> {
                if(rc != null) {
                    runCfg = rc;
                    DBHandlerBase.setRunConfig(runCfg);
                    return HandleResult.OK;
                }
                
                if(PartitionConfig.instance().mode == DeployMode.SINGLETON) {
                    runCfg =  RunConfig.createDefault();
                    DBHandlerBase.setRunConfig(runCfg);
                    LOG.warn("No valid webdb config, user default in singleton mode");
                    return HandleResult.OK;
                }
                //与公司内网的route.cfg不同，路由信息变更，如果错了，只会出现暂时的错乱
                //数据库配置错乱，会导致数据乱写，所以一旦请求配置失败则整个失败
                return new HandleResult(RetCode.INVALID_NODE, "can't get db run config");
            });            
        }, Pool);
    }
    
    @Override
    public CompletableFuture<HandleResult> afterLoad(IServiceServer server, ServiceInfo si, String pwd) {
        AbsSearchDBWorker.init(FileUtil.addPath(server.homeDir(), IConst.SYS_CONF_DIR));
        //设置数据库备份定时任务
        if(runCfg.needBackup()) {
            SqliteWorker.setBackupTimer(si);
        }
        this.dbInit(si);

        return super.afterLoad(server, si, pwd);
    }

    @Override
    public void watch(IServiceServer server, ServiceInfo si) {
        this.dbReport(si); //除了刷新服务状态外，还需要刷新数据库状态
        super.watch(server, si);
    }

    private CompletableFuture<HandleResult> dbReport(ServiceInfo si) {
        String addr = ChannelConfig.instance().localHttpAddr();
        /*
         * 本实例可支持的服务+数据库信息上报给bios，在数据库路由时用到此数据。
         * 因为webdb是普遍被依赖的服务，所以在afterLoad中就做这样的初始化，
         * 以使得其他服务在startup时，可以获得数据库路由
         */
        ServiceReqBuilder builder = new ServiceReqBuilder(si, SERVICE_BIOS)
                .url("/status/dbreport")
                .traceId(si.name)
                .cid(CompanyInfo.instance().id) //不区分公司，填首要的就行
                .put(SEG_PARTID, PartitionConfig.instance().partition)
                .put(SEG_ADDR, addr)
                .put(SEG_DBS, runCfg.dbsList())
                .appToken("*");
        
        return BiosClient.post(builder).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to report db node@{} to bios of partition {}", addr, PartitionConfig.instance().partition, e);
                return;
            }

            if(hr.code != RetCode.OK) {
                LOG.error("Fail to report db node@{} to bios of partition {},result:{}", addr, PartitionConfig.instance().partition, hr.brief());
            } else {
                LOG.debug("Success to report db node@{} to bios", addr);
            }
        }, Pool);
    }
    
    private CompletableFuture<HandleResult> dbInit(ServiceInfo si) {
        String addr = ChannelConfig.instance().localHttpAddr();
        /*
         * 本实例可支持的服务+数据库信息上报给bios，在数据库路由时用到此数据。
         * 因为webdb是普遍被依赖的服务，所以在afterLoad中就做这样的初始化，
         * 以使得其他服务在startup时，可以获得数据库路由
         */
        ServiceReqBuilder builder = new ServiceReqBuilder(si, SERVICE_BIOS)
                .url("/status/dbinit")
                .traceId(si.name)
                .cid(CompanyInfo.instance().id) //不区分公司，填首要的就行
                .put(SEG_PARTID, PartitionConfig.instance().partition)
                .put(SEG_ADDR, addr)
                .put(SEG_VER, si.version)
                .put(SEG_DBS, runCfg.dbsList())
                .appToken("*");
        
        return BiosClient.post(builder).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to create db node@{} in bios of partition {}", addr, PartitionConfig.instance().partition, e);
                return;
            }

            if(hr.code != RetCode.OK) {
                LOG.error("Fail to create db node@{} in bios of partition {},result:{}", addr, PartitionConfig.instance().partition, hr.brief());
            } else {
                LOG.debug("Success to create db node@{} in bios", addr);
            }
        }, Pool);
    }

    @Override
    public CompletableFuture<HandleResult> destroy(IServiceServer server, ServiceInfo si) {
        super.destroy(server, si);
        String addr = ChannelConfig.instance().localHttpAddr();

        ServiceReqBuilder builder = new ServiceReqBuilder(si, SERVICE_BIOS)
                .url("/status/dbremove")
                .cid(CompanyInfo.instance().id) //不区分公司
                .traceId(si.name)
                .put(SEG_PARTID, Integer.toString(PartitionConfig.instance().partition))
                .put(SEG_ADDR, addr)
                .put(SEG_DBS, runCfg.dbsList())
                .appToken("*");
        
        //delete不支持body，所以使用post
        return BiosClient.post(builder).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to remove node@{} in bios", addr, e);
            } else if(hr.code != RetCode.OK) {
                LOG.error("Fail to remove node@{} in bios,result:{}", addr, hr.brief());
            } else {
                LOG.info("Success to remove node@{} in bios", addr);
            }
            AbsRDBWorker.destroy();
            TreeDBWorker.destroy();
            AbsSearchDBWorker.destroy();
        }, Pool);
    }
    
    private CompletableFuture<RunConfig> getConfig(ServiceInfo si) {
        ServiceReqBuilder req = new ServiceReqBuilder(si, SERVICE_BIOS)
                .url("/db/getConfig")
                .appendPara("addr", ChannelConfig.instance().localHttpAddr())
                .cid(CompanyInfo.instance().id) //不区分公司
                .appToken("*")
                .traceId(si.name + "_get_dbconfig");
        return BiosClient.get(req).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to download db cfg,result:{}", hr.brief());
                return null;
            }
            String cfg = ValParser.getAsStr(hr.data, "cfg").trim();
            if(StringUtil.isEmpty(cfg) || !cfg.matches("^\\[(\\{.*\\})+\\]$")) {
                LOG.error("Invalid db cfg,content:{}", cfg); //单例情况下无设置
                return null;
            }
            File cfgFile = RunConfig.configFile(si.homeDir);
            FileUtil.writeFile(cfgFile, cfg, DEFAULT_CHARSET);
            return RunConfig.parse(cfgFile);
        }, Pool);
    }
}
