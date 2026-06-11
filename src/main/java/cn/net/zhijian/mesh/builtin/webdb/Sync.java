package cn.net.zhijian.mesh.builtin.webdb;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.dbworker.SqliteWorker.SqliteBuilder;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsConnection;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker.AbsRDBWBuilder;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 如果库不存在，则会创建；其他地方，如果库不存在，直接返回内部错误。
 *
 * @author flyinmind of csdn.net
 *
 */
final class Sync extends DBHandlerBase {
    private static final Logger LOG = LogUtil.getInstance();

    public Sync(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        Map<String, Object> params = req.params();
        String service = ValParser.getAsStr(params, DB_REQ_SERVICE);
        String db = ValParser.getAsStr(params, DB_REQ_DB);

        AbsRDBWorker worker = AbsRDBWorker.instance(req.cid(), service, db);
        if(worker == null) {
            ServiceInfo si = req.serviceInfo();
            int dbNo = ValParser.getAsInt(params, DB_REQ_DBNO, -1);
            AbsRDBWBuilder builder = runCfg.getBuilder(dbNo, si, req.cid(), service, db);
            if(builder == null) { //如果run.cfg中无此dbNo，就无法获取builder
                LOG.error("Fail to get db builder of {}.{}", service, db);
                return futureResult(RetCode.INTERNAL_ERROR, "fail to get db builder");
            }
            builder.createIfAbsent(true);
            builder.longToStr(false);
            if(builder instanceof SqliteBuilder) { //远程备份库无需再启动本地备份
                ((SqliteBuilder)builder).backup(false);
            }
            if((worker = AbsRDBWorker.instance(req.cid(), builder)) == null) {
                return futureResult(RetCode.INTERNAL_ERROR, "fail to get db worker");
            }
        }

        try(AbsConnection conn = worker.getWriteConn()) {
            boolean ok = false;
            String s;
            List<String> sqls = ValParser.getAsStrList(params, RDB_REQ_SQLS);

            worker.beginTransaction(conn); //在一个事务中执行所有sql
            try {
                for(String sql : sqls) {
                    s = sql.substring(0, 2).toLowerCase();
                    if(s.equals("in")) { //忽略insert的主键冲突，update的主键冲突目前无法忽略
                        sql = AbsRDBWorker.addInsertIgnore(sql);
                    }
                    worker.executeRawDML(conn, sql);
                }
                ok = true;
            } catch(Exception e) {
                LOG.error("Fail to execute sql:`{}`", sqls, e);
                return futureResult(RetCode.DATA_WRONG, "invalid sql");
            } finally {
                worker.endTransaction(conn, ok);
            }
        } catch(MeshException se) {
            LOG.error("Fail to open connection of db {}.{}", service, db, se);
            return futureResult(RetCode.DB_ERROR);
        }

        return futureResult();
    }
}