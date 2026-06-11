package cn.net.zhijian.mesh.frm.process;

import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.dbworker.TreeDBWorker;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;

/**
 * 树状本地单例数据库
 * 不支持分库，不支持备份，通常只有一个查询数据库
 * @author flyinmind of csdn.net
 *
 */
public class LocTreeDBProcessor extends TreeDBProcessor {
    private static final Logger LOG = LogUtil.getInstance();

    public LocTreeDBProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        ServiceInfo si = serviceInfo();
        TreeDBWorker dbWorker = (TreeDBWorker)si.getLocalDBWorker(TREEDB, this.db);
        if(dbWorker == null) {
            LOG.error("Can't find tree db {}/{}", si.name, this.db);
            return futureResult(RetCode.DB_ERROR, "not exists");
        }
        HandleResult dbReqData = buildRequest(req, db, respData, LOCAL_DBNO);
        if(dbReqData.code != RetCode.OK || dbReqData.data == null) {
            if(dbReqData.code == RetCode.NO_OPERATION) {
                return CompletableFuture.completedFuture(HandleResult.OK);
            }
            return CompletableFuture.completedFuture(dbReqData);
        }

        req.putAll(dbReqData.data);
        HandleResult hr = dbWorker.handleRequest(req, respData);
        return CompletableFuture.completedFuture(hr);
    }
}