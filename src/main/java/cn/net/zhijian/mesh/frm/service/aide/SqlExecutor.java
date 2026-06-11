package cn.net.zhijian.mesh.frm.service.aide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsDBProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker.SqlType;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.para.ParameterInfo;

/**
 * 自动为每个服务添加此接口，om用此接口在服务的数据库上执行命令。
 * 因为是服务自身的接口，所以不存在权限问题。
 * 此接口不支持数据库分片
 * 私有云中在cn.net.zhijian.mesh.frm.service.vCloud.SqlExecute中调用；
 * 公有云中在cn.net.zhijian.mesh.builtin.company.SqlExecute中调用；
 * @author flyinmind of csdn.net
 *
 */
public class SqlExecutor extends AbsDBProcessor {
    public SqlExecutor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String db = req.getString("db");
        long start = System.currentTimeMillis();

        return sendDBRequest(req, RDB_API_URL, db, resp).thenComposeAsync(hr -> {
            if(hr.code == RetCode.OK) {
                String sql = req.getString("sql");
                SqlType type = AbsRDBWorker.getSqlType(sql);
                Map<String, Object> data = hr.data != null ? hr.data : new HashMap<>();
                data.put("type", type.name());
                data.put("time", System.currentTimeMillis() - start);
                return futureResult(data);
            }
            return CompletableFuture.completedFuture(hr);
        }, Pool);
    }

    @Override
    protected HandleResult buildRequest(AbsServerRequest req, String db, Map<String, Object> respData, int dbNo) {
        Map<String, Object> reqData = new HashMap<>();
        reqData.put(DB_REQ_DB, db);
        reqData.put(DB_REQ_DBNO, dbNo);
        reqData.put(DB_REQ_TIME, req.reqTime);
        String sql = req.getString("sql");
        SqlType type = AbsRDBWorker.getSqlType(sql);
        
        Map<String, Object> statement = new HashMap<>();

        switch(type) {
        case SELECT:
            statement.put(SQL_NAME, "list");
            statement.put(SQL_MULTI, true);
            statement.put(SQL_METAS, "cols");
            statement.put(SQL_SQL, sql);
            reqData.put(DB_REQ_ISWRITE, false);
            break;
        case INSERT:
        case UPDATE:
        case DELETE:
            statement.put(SQL_NAME, "execute");
            statement.put(SQL_SQL, AbsRDBWorker.modifyDMLSql(sql, Long.toString(req.reqTime)));
            reqData.put(DB_REQ_ISWRITE, true);
            break;
        default:
            return new HandleResult(RetCode.WRONG_PARAMETER, "invalid sql");
        }
        List<Map<String, Object>> sqls = new ArrayList<>();
        sqls.add(statement);
        
        statement.put(SQL_NEEDMODIFY, false);
        statement.put(SQL_NEEDCOMPILE, false);
        reqData.put(RDB_REQ_SQLS, sqls);
        return new HandleResult(RetCode.OK, reqData);
    }
    
    public static RequestInfo getRequestInfo() {
        ParameterInfo sql = new ParameterInfo.Builder("sql", ParameterInfo.TYPE_STRING)
                .setMax(1000).setMin(8).build();
        ParameterInfo db = new ParameterInfo.Builder("db", ParameterInfo.TYPE_STRING)
                .setRegular("^[a-zA-Z0-9_]{1,30}$").build();
        
        return new RequestInfo(new ParameterInfo[] {db, sql});
    }
}