package cn.net.zhijian.mesh.builtin.bios;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsConnection;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 同步bios相关的数据，与webdb的不同，走单独的同步
 *
 * @author flyinmind of csdn.net
 *
 */
final class Sync extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();

    public Sync(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        Map<String, Object> params = req.params();
        String db = ValParser.getAsStr(params, IDBConst.DB_REQ_DB);
        ServiceInfo si = serviceInfo();

        AbsRDBWorker worker = (AbsRDBWorker)si.getLocalDBWorker(IDBConst.RDB, db);
        if(worker == null) {
            return futureResult(RetCode.INTERNAL_ERROR, "fail to get db worker");
        }

        try(AbsConnection conn = worker.getWriteConn()) {
            List<String> sqls = ValParser.getAsStrList(params, IDBConst.RDB_REQ_SQLS);
            boolean ok = false;
            String s;
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
            LOG.error("Fail to open connection of db {}.{}", si.name, db, se);
            return futureResult(RetCode.DB_ERROR, "db error");
        }

        return futureResult();
    }
}