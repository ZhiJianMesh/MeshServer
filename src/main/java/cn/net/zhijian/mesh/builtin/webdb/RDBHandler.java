package cn.net.zhijian.mesh.builtin.webdb;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker.AbsRDBWBuilder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 关系型数据库处理
 * @author flyinmind of csdn.net
 * {
 *   "service":"serviceName",
 *   "db":"dbName",
 *   "write":"is there a write SQL",
 *   "any":"true/false",
 *   "sharding":123,
 *   "sqls":[{
 *       "name":"result name of the SQL statement",
 *       "needModify":"add update_time segment",
 *       "merge":"put the result directly into the 'data' map",
 *       "multi":"multiple lines",
 *       "when":"@{CONDITION|!xxx,'i.==',0}&&true",
 *       "metas":"with column name when return query result,each/none/name",
 *       "sql":"CRUD SQL"
 *   }...]
 * }
 */
public final class RDBHandler extends DBHandlerBase {
    private static final Logger LOG = LogUtil.getInstance();

    public RDBHandler(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    /**
     *
     * @param req 从请前方传递来的参数
     * @param respData  前面的handle返回的参数，多个handle的返回累积到resp中。
     *              此参数不可修改。webdb中不会使用，因为只有一个handler
     * @return 执行结果
     */
    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        Map<String, Object> params = req.params();
        String dbName = ValParser.getAsStr(params, DB_REQ_DB);
        String service = req.token().caller;
        
        AbsRDBWorker db = AbsRDBWorker.instance(req.cid(), service, dbName);
        if(db == null) {
            int dbNo = ValParser.getAsInt(params, DB_REQ_DBNO, -1);
            AbsRDBWBuilder builder = runCfg.getBuilder(dbNo, serviceInfo, req.cid(), service, dbName);
            if((db = AbsRDBWorker.instance(dbNo, builder)) == null) {
                LOG.error("Can't find db {}/{}/{}", req.cid(), service, dbName);
                return futureResult(RetCode.DB_ERROR, "db not exists");
            }
        }
        
        int sharding = ValParser.getAsInt(params, DB_REQ_SHARDING, -1);
        if(sharding >= 0 && !db.isValidSharding(sharding)) {
            LOG.error("Invalid sharding received {}", sharding);
            return futureResult(RetCode.INVALID_NODE, "invalid sharding");
        }

        try {
            HandleResult hr = db.handleRequest(req, respData);
            return CompletableFuture.completedFuture(hr);
        } catch(Exception e) {
            LOG.error("Fail to execute {},cid:{},caller:{},dbworker:`{}`",
                StringUtil.mapToStr(req.params(), 23), req.cid(), service, db, e);
            return futureResult(HandleResult.InternalError);
        }
    }
}