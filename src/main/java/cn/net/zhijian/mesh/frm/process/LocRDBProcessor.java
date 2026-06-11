package cn.net.zhijian.mesh.frm.process;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * 只能执行本地数据库单例，
 * 不支持分库，不支持备份，通常只有一个查询数据库，比如行政分区数据库
 * @author flyinmind of csdn.net
 *
 */
public class LocRDBProcessor extends RDBProcessor {
    private static final Logger LOG = LogUtil.getInstance();

    public LocRDBProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        ServiceInfo si = serviceInfo();
        AbsRDBWorker dbWorker = (AbsRDBWorker)si.getLocalDBWorker(RDB, this.db);
        if(dbWorker == null) {
            LOG.error("Can't find db {}/{}", si.name, this.db);
            return futureResult(RetCode.DB_ERROR, "db not exists");
        }
        
        HandleResult dbReqData = buildRequest(req, db, respData, LOCAL_DBNO);
        if(dbReqData.code != RetCode.OK || dbReqData.data == null) {
            if(dbReqData.code == RetCode.NO_OPERATION) {
                return CompletableFuture.completedFuture(HandleResult.OK);
            }
            return CompletableFuture.completedFuture(dbReqData);
        }

        req.putAll(dbReqData.data);
        try {
            HandleResult hr = dbWorker.handleRequest(req, respData);
            return CompletableFuture.completedFuture(hr);
        } catch(Exception e) {
            LOG.error("Fail to execute {}", StringUtil.mapToStr(req.params(), 23), e);
            return futureResult(HandleResult.InternalError);
        }
    }
}
