package cn.net.zhijian.mesh.frm.process;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.client.DBClient.DBReqBuilder;
import cn.net.zhijian.mesh.client.DBClient.DocAction;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsSearchDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;

/**
 * 全文搜索本地单例数据库
 * 不支持分库，不支持备份，通常只有一个查询数据库，比如行政分区数据库
 * @author flyinmind of csdn.net
 *
 */
public class LocSearchDBProcessor extends SearchDBProcessor {
    private static final Logger LOG = LogUtil.getInstance();

    public LocSearchDBProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        ServiceInfo si = serviceInfo();
        AbsSearchDBWorker dbWorker = (AbsSearchDBWorker)si.getLocalDBWorker(SEARCHDB, this.db);
        if(dbWorker == null) {
            LOG.error("Can't find search db {}/{}", si.name, this.db);
            return futureResult(RetCode.DB_ERROR, "not exists");
        }
        
        if(this.action.equals(ACTION_GET)) {
            String search = translateElements(this.content, req, respData); //待搜索的字符串
            String limit = translateElements(this.limit, req, respData); //解释之后必须是一个大于0的数字
            String table = translateElements(this.table, req, respData); 
            DBReqBuilder builder = new DBReqBuilder(serviceInfo(), this.db, LOCAL_DBNO)
                .readSlave(this.readSlave);
            builder.traceId(req.traceId)
                .cid(req.cid())
                .putAll(DocAction.search(search).table(table).toMap())
                .put(DB_REQ_TIME, req.reqTime)
                .put(SEARCHDB_REQ_LIMIT, limit);
            req.putAll(builder.data());
        } else {
            HandleResult dbReqData = buildRequest(req, db, respData, LOCAL_DBNO); //搜索时单例运行，所以无需多库汇总
            if(dbReqData.code != RetCode.OK || dbReqData.data == null) {
                return CompletableFuture.completedFuture(dbReqData);
            }
            req.putAll(dbReqData.data);
        }

        HandleResult hr = dbWorker.handleRequest(req, respData);
        return CompletableFuture.completedFuture(hr);
    }
    
    @Override
    protected ScriptElement[] parseTable(Set<String> availableParas, Map<String, Object> cfg) {
        return new ScriptElement[] {
            ScriptElement.stringElement(AbsSearchDBWorker.UNDEFINED_TABLE, IConst.EMPTY_STR, null)
        };
    }
}