package cn.net.zhijian.mesh.builtin.webdb;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.dbworker.SearchDBWorker;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker.AbsRDBWBuilder;
import cn.net.zhijian.mesh.frm.abs.AbsSearchDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;

/**
 * 搜索数据库处理类
 * @author flyinmind of csdn.net
 *
 */
public final class SearchDBHandler extends DBHandlerBase {
    private static final Logger LOG = LogUtil.getInstance();

    public SearchDBHandler(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String dbName = req.getString(DB_REQ_DB);
        String service = req.token().caller;

        AbsSearchDBWorker db = SearchDBWorker.instance(req.cid(), service, dbName);
        if(db == null) {
            int dbNo = req.getInt(DB_REQ_DBNO, -1);
            AbsRDBWBuilder builder = runCfg.getBuilder(dbNo, serviceInfo, req.cid(), service, dbName);
            if(builder == null) {
                LOG.error("Fail to get db builder of {}.{}", service, dbName);
                return futureResult(RetCode.INTERNAL_ERROR, "fail to get db builder");
            }

            builder.longToStr(false);
            if((db = SearchDBWorker.instance(dbNo, builder)) == null) {
                LOG.error("Can't find search db {}/{}", service, dbName);
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
