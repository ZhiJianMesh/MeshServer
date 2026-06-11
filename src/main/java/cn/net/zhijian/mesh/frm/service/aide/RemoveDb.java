package cn.net.zhijian.mesh.frm.service.aide;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 用于调用服务初始化接口，比如CRM的工作流初始化接口，此类接口的tokenChecker应为APP。
 * 接口实现要求可重入，且接口只接受OMKey认证，接口名称以"__"开头，只接受get方法。
 * 初始化调用是异步执行的，不影响启动的成败。
 * 云上调用时，只能在OM服务中调用，因为只有OM具有OM的私钥
 * 如果需要区分公司，则需要传入cid头部。
 * @author flyinmind of csdn.net
 *
 */
public class RemoveDb extends AideBase {
    private static final Logger LOG = LogUtil.getInstance();

    public RemoveDb(IServiceServer server, ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(server, serviceInfo, apiInfo, processName);
    }
    
    public RemoveDb(IServiceServer server, ServiceInfo serviceInfo, ApiInfo apiInfo) {
        this(server, serviceInfo, apiInfo, API_SERVICE_RMVDB);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        ServiceInfo si = req.serviceInfo();
        String dbCfgFileName = FileUtil.addPath(si.homeDir, DATABASE_CONFIG_FILE);
        File dbCfgFile = new File(dbCfgFileName);
        List<Object> dbDefines;

        if(!dbCfgFile.exists()) { //存在时，才需删除数据库
            return HandleResult.future(RetCode.OK, "no db defined");
        }
        dbDefines = JsonUtil.jsonFileToList(new File(dbCfgFileName), true);
        if (dbDefines == null || dbDefines.isEmpty()) {
            LOG.error("Invalid database config file `{}`", dbCfgFileName);
            return HandleResult.future(RetCode.INTERNAL_ERROR, "invalid database.cfg");
        }
        return req.getDbNo().thenComposeAsync(dbNo -> {
            if(dbNo < 0) {
                return futureResult(RetCode.INTERNAL_ERROR, "dbNo not set");
            }
            return handle(req, dbNo, dbDefines);
        }, Pool);        
    }
    
    private CompletableFuture<HandleResult> handle(AbsServerRequest req, int dbNo, List<Object> dbDefines) {
        List<CompletableFuture<HandleResult>> actions = new ArrayList<>();
        Map<String, HandleResult> results = new HashMap<>();
        ServiceInfo si = serviceInfo();
        int cid = req.cid(); //必须传递公司的正确id，否则会删除了错误公司的数据库
        int partId = PartitionConfig.instance().partition;
        LOG.info("Create {}'s db of cid({}) in partition {}", si.name, cid, partId);
        /*
         *dbDefines:[
         * {name:"xxx",type:"rdb",versions:[
         *     {version:"xxx",sqls:["sql1","sql2"...]},...
         *   ]
         * }
         * ...
         *]
         */
        String appToken = BiosClient.appToken(si, SERVICE_WEBDB, "*");
        StringBuilder dbs = new StringBuilder(4096);
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
            
            ServiceReqBuilder builder = new ServiceReqBuilder(si, SERVICE_BIOS)
                .url("/db/dbNodes?dbNo=" + dbNo + "&partId=" + partId)
                .traceId(req.traceId)
                .cid(cid)
                .appToken("*");
            CompletableFuture<HandleResult> cf = BiosClient.get(builder).thenComposeAsync(hr -> {
                List<Object> nodes; //每行包括addr,sharding,slaves,ver,level
                if(hr.code != RetCode.OK || hr.data == null
                   || (nodes = ValParser.getAsList(hr.data, "nodes")) == null) {
                    LOG.error("Fail to get dbnodes of {}.{}, result:{}", si.name, dbName, hr.brief());
                    return CompletableFuture.completedFuture(hr);
                }
                LOG.debug("Remove dbs for service `{}` on {}", si.name, nodes);
                String type = ValParser.getAsStr(dbCfg, "type");
                return removeOneDB(cid, si, dbName, type, nodes, appToken, req.traceId);
            }, Pool).whenCompleteAsync((hr, e) -> {
                if(e != null) {
                    LOG.error("Fail to remove db of {}.{} on {}", cid, si.name, dbName, e);
                    results.put(dbName, new HandleResult(RetCode.INTERNAL_ERROR, "fail to remove " + dbName));
                } else {
                    results.put(dbName, hr);
                }
            }, Pool);
            actions.add(cf);
        }

        return CompletableFuture.allOf(actions.toArray(new CompletableFuture<?>[dbDefines.size()])).thenComposeAsync(_v -> {
            Map<String, Object> d = new HashMap<>();
            for(Map.Entry<String, HandleResult> e : results.entrySet()) {
                d.put(e.getKey(), e.getValue().toMap());
            }
            d.put("dbs", dbs.toString());
            return HandleResult.future(d);
        }, Pool);
    }

    /**
     *
     * @param cid 公司id
     * @param si 服务信息
     * @param db 数据库名
     * @param type 类型
     * @param nodes 需要初始化的节点
     * @param appToken app token
     * @param traceId 跟踪id
     * @return 执行结果
     */
    private CompletableFuture<HandleResult> removeOneDB(int cid, ServiceInfo si, String db, String type,
            List<Object> nodes, String appToken, String traceId) {
        List<CompletableFuture<HandleResult>> actions = new ArrayList<>();
        Map<String, HandleResult> results = new HashMap<>();
        ServiceReqBuilder request = new ServiceReqBuilder(si, SERVICE_WEBDB)
                .url("/om/remove").traceId(traceId).cid(cid)
                .token(appToken)
                .appendPara(IDBConst.DB_REQ_SERVICE, si.name)
                .appendPara(IDBConst.DB_REQ_DB, db)
                .appendPara("type", type);
        
        for(Object o : nodes) { //每个db需要发送到所有的节点
            Map<String, Object> node = ValParser.parseObject(o);
            String addr = ValParser.getAsStr(node, "addr"); //不处理数据库从节点
            LOG.debug("remove db of {}.{} on {},cid:{}", si.name, db, addr, cid);
            NodeAddress dn = new NodeAddress(si.name, addr, 1000, 0);
            CompletableFuture<HandleResult> cf = ServiceClient.serviceDelete(dn, request).whenCompleteAsync((rmvHr, e) -> {
                if(e != null) {
                    LOG.error("Fail to remove db {}.{} on {}", si.name, db, addr, e);
                    results.put(addr, new HandleResult(RetCode.INTERNAL_ERROR, "fail to init " + db));
                } else {
                    results.put(addr, rmvHr);
                }
            }, Pool);

            actions.add(cf);
        }

        return CompletableFuture.allOf(actions.toArray(new CompletableFuture<?>[nodes.size()])).thenComposeAsync(_v -> {
            Map<String, Object> d = new HashMap<>();
            for(Map.Entry<String, HandleResult> e : results.entrySet()) {
                d.put(e.getKey(), e.getValue().toMap());
            }
            return futureResult(d);
        }, Pool);
    }
}