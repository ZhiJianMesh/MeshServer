package cn.net.zhijian.mesh.builtin.webdb;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.dbworker.SearchDBWorker;
import cn.net.zhijian.mesh.dbworker.TreeDBWorker;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker.AbsRDBWBuilder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 此操作只可以在OM上操作，在实例上初始化表结构，也可以按版本执行升级操作。
 * 整个系统，只有在此接口中，如果库不存在，则会创建；
 * 其他地方，如果库不存在，直接返回内部错误。
 *
 * @author flyinmind of csdn.net
 *
 */
final class InitTables extends DBHandlerBase {
    private static final Logger LOG = LogUtil.getInstance();

    public InitTables(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        Map<String, Object> params = req.params();
        String service = req.token().caller;//必须使用服务自身的私钥签名token
        String type = ValParser.getAsStr(params, "type");
        String db = ValParser.getAsStr(params, DB_REQ_DB);
        int dbNo = ValParser.getAsInt(params, DB_REQ_DBNO, -1);
        ServiceInfo si = req.serviceInfo();
        int cid = req.cid();

        //sdb,tdb,rdb都是基于Sqlite实现的
        AbsRDBWBuilder builder = runCfg.getBuilder(dbNo, si, cid, service, db);
        if(builder == null) {
            LOG.error("Fail to get db builder of {}.{}.{}", cid, service, db);
            return futureResult(RetCode.INTERNAL_ERROR, "fail to get db builder");
        }

        builder.createIfAbsent(true);
        if(type.equals(TREEDB)) {
            if(TreeDBWorker.instance(dbNo, builder) != null) {
                LOG.info("Create treedbworker {}.{}.{},slaves:{}", cid, service, db, builder.slaves());
                return futureResult();
            }
            return futureResult(RetCode.INTERNAL_ERROR);
        }

        if(type.equals("sdb")) {
            if(SearchDBWorker.instance(dbNo, builder) != null) {
                LOG.info("Create searchdbworker {}.{}.{},slaves:{}", cid, service, db, builder.slaves());
                return futureResult();
            }
            return futureResult(RetCode.INTERNAL_ERROR);
        }

        AbsRDBWorker worker = AbsRDBWorker.instance(dbNo, builder);
        if(worker == null) {
            LOG.error("Fail to open SqliteWorker {}.{}.{}", cid, service, db);
            return futureResult(RetCode.INTERNAL_ERROR, "fail to get db worker");
        }
        LOG.info("Create sqlitedbworker {}.{}.{},slaves:{}", cid, service, db, builder.slaves());

        /*
         * sql字符串可以有换行，jsonToMapWithLF会将字符串中的换行替换成\n
         * [{minVer:"min", "maxVer":"max","toVer":"to",sqls:["sql1","sql2"...]},{...}...]
         * 第一个元素必须是`最新版本号`，后面的都是`待升级版本号`，
         * 执行时，如果是初始化的情况，第一个执行后，后面的都不会执行。
         */
        String version = ValParser.getAsStr(params, "version"); //升级后的版本号
        if(StringUtil.isEmpty(version)) {
            return futureResult(RetCode.WRONG_PARAMETER, "invalid version");
        }
        
        String tabledef = ValParser.getAsStr(params, "tabledef");
        if(tabledef.length() < 10) {
            return futureResult(RetCode.WRONG_PARAMETER, "invalid tabledef");
        }
        
        tabledef = AbsDBWorker.translateSql(tabledef, req, resp); //解析sql，其中可以使用占位符
        List<Object> defines = JsonUtil.jsonToListWithLF(tabledef);
        if(defines == null || defines.isEmpty()) {
            LOG.error("Invalid table defines in {}.{}.{}.{}", cid, service, db, version);
            return futureResult(RetCode.INTERNAL_ERROR, "invalid table defines of version " + version);
        } else if(LOG.isDebugEnabled()) {
            LOG.debug("tabledef:`{}`", tabledef);
        }

        if(!worker.execInitDDLs(defines, version)) {
            LOG.error("Fail to execute init sqls of {}.{}.{} to {}", cid, service, db, version);
            return futureResult(RetCode.INTERNAL_ERROR, "fail to execute init to " + version);
        }
        return futureResult();
    }
}