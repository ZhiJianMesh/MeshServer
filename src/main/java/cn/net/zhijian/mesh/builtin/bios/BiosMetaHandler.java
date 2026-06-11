package cn.net.zhijian.mesh.builtin.bios;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.dbworker.TreeDBWorker;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.process.TreeDBProcessor;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

/**
 * service
 *  |_service1
 *      |_pub/pri key
 *      |_dnsMode->random
 *      |_caller
 *        |_service2->features
 *        |_service3->features
 *      |_dbs(一种特殊被调服务)
 *        |_db1
 *          |_tabledef->xxx
 *          |_features->rdb/tdb/sdb
 *        |_db2
 *          |_tabledef->yyy
 *      |_configs
 *        |_codebooks->...
 *        |_keyName->userKey:cipheredKey
 *      |_coffer
 *        |_keyName->AbsPassword
 * bios meta db是一种特殊的db，基于treedb实现，同一时间，只能在主节点上读写。
 * 请求bios db，只需要使用service的私钥签名即可
 * 在bios中记录了所有服务的公钥，用于验证请求合法性。
 * metadb只会读写一个实例，在rdbworker层同步到其他实例，
 * 或同步到slave实例，最终保证每个实例是一致的
 * 
 * @author flyinmind of csdn.net
 *
 */
public final class BiosMetaHandler extends TreeDBProcessor {
    static final String DB = "mesh_meta"; //注册库，记录meta信息

    private static final Logger LOG = LogUtil.getInstance();
    private static TreeDBWorker meta;
    
    public BiosMetaHandler(ServiceInfo si, ApiInfo apiInfo, String processName) {
        super(si, apiInfo, processName);
        this.db = DB;
    }
    
    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        List<Map<String, Object>> reqList = buildRequestList(req, respData);
        if(reqList == null) {
            LOG.error("Fail to build request list in {}", name());
            return HandleResult.future(RetCode.API_ERROR, "fail to build request list");
        }

        int i = 0;
        for(Map<String, Object> opr : reqList) {
            i++;
            TreeDBWorker.DBResultCode drc = meta.handleRequest(opr, respData);
            if(drc == TreeDBWorker.DBResultCode.OK) {
                if(any) { //设置了any，则第一个操作成功，则结束
                    break;
                }
            } else {
                List<Integer> ignores = RetCode.parseCodes(ValParser.getAsList(opr, TREEDB_REQ_IGNORES));
                //只要发生错误，立刻结束，但是前面的响应内容仍然返回
                int code = TreeDBWorker.translateCode(drc);
                if(TreeDBWorker.canIgnore(code, ignores)) {
                    LOG.debug("Fail to execute({}):{},error({}) is ignored,{}", i, opr, code, req.traceId);
                    continue;
                }
                if(LOG.isDebugEnabled()) {
                    LOG.warn("Fail to execute No.{}:{},drc:{},code:{},info:{},ignores:{},{}",
                            i, opr, drc.name(), code, RetCode.getInfo(code), ignores, req.traceId);
                }
                return HandleResult.future(code,  "failed in action " + i);
            }
        }
        
        return HandleResult.future();
    }


    @Override
    protected String getDBConfig(Map<String, Object> cfg) {
        return DB;
    }
    
    static void setMetaDb(TreeDBWorker meta) {
        BiosMetaHandler.meta = meta;
    }
}