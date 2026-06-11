package cn.net.zhijian.mesh.builtin.webdb;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.dbworker.TreeDBWorker;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker.AbsRDBWBuilder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;
/**
 * 树形数据库处理
 * 受制于表结构的设计，只能按dir分库，不能按完整路径分库，
 * 分库在客户端实现，此处的实现是服务端，不处理分库
 * @author flyinmind of csdn.net
 * {
 *   "service":"serviceName",
 *   "db":"dbName",
 *   "actions":[{
 *       "action":"...",
 *       "name":"...",
 *      "value":"...",
 *      "as":"..."
 *   },...]
 * }
 */
public final class TreeDBHandler extends DBHandlerBase {
    private static final Logger LOG = LogUtil.getInstance();

    public TreeDBHandler(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    /**
     *
     * @param req 从请前方传递来的参数
     * @param resp  前面的handle返回的参数，多个handle的返回累积到resp中。此参数不可修改。
     *              webdb中不会使用，因为只有一个handler，无后继handler从其中获得上一步的结果
     * @return 处理结果
     */
    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String dbName = req.getString(DB_REQ_DB);
        String service = req.token().caller;
        TreeDBWorker db = TreeDBWorker.instance(req.cid(), service, dbName);
        if(db == null) {
            int dbNo = req.getInt(DB_REQ_DBNO, -1);
            AbsRDBWBuilder builder = runCfg.getBuilder(dbNo, serviceInfo, req.cid(), service, dbName);
            if(builder == null) {
                LOG.error("Fail to get db builder of {}.{}", service, dbName);
                return futureResult(RetCode.INTERNAL_ERROR, "fail to get db builder");
            }
            builder.longToStr(false);
            if((db = TreeDBWorker.instance(dbNo, builder)) == null) {
                LOG.error("Can't find tree db {}/{}", service, dbName);
                return futureResult(RetCode.DB_ERROR, "not exists");
            }
        }

        int sharding = req.getInt(DB_REQ_SHARDING, -1);
        if(sharding >= 0 && !db.getCoreDb().isValidSharding(sharding)) {
            LOG.error("Invalid sharding received {}", sharding);
            return futureResult(RetCode.INVALID_NODE, "invalid sharding");
        }

        HandleResult hr = db.handleRequest(req, resp);
        return CompletableFuture.completedFuture(hr);
    }
}