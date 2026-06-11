package cn.net.zhijian.mesh.builtin.bios;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.process.RDBProcessor;

/**
 * bios db是一种特殊的db，基于rdb实现，支持在端侧分片。
 * 请求bios db，只需要使用service的私钥签名即可
 * 在bios中记录了所有服务的公钥，用于验证请求合法性。公钥可以在om服务中设置，或启动时，自动设置。
 * statusdb可以分片读写，在rdbworker层同步到slave实例，每个实例上保存若干分片的数据

 * @author flyinmind of csdn.net
 *
 */
public final class BiosDBHandler extends RDBProcessor {
    static final String DB = "mesh_status"; //状态库，每个实例定时上报其上各服务的状态
    private final AbsRDBWorker rdb;

    public BiosDBHandler(ServiceInfo si, ApiInfo apiInfo, String processName) {
        super(si, apiInfo, processName);
        //加载顺序：ServiceInfo->Watcher.beforeLoad(load local dbs)->new Processor->Processor.parse
        //所以此处可以使用si.getLocalDBWorker
        this.db = DB;
        this.rdb = (AbsRDBWorker)si.getLocalDBWorker(RDB, this.db);
        if(this.rdb == null) {
            throw new NullPointerException(this.db + " not exists");
        }
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        //客户端请求时按服务名称均衡到不同bios实例中，
        //如果接受到请求了，数据库通常是能执行的，因为是本地数据库。
        //任何一个实例执行，数据都会同步到其他实例
        //因为同步的关系，服务或数据库的状态不能在任意时刻都一致，最终会同步成一致
        HandleResult buildHr = buildRequest(req, this.db, respData, LOCAL_DBNO);
        if(buildHr.code != RetCode.OK || buildHr.data == null) {
            if(buildHr.code == RetCode.NO_OPERATION) { //如果一个sql也没有，则不必发送请求
                return CompletableFuture.completedFuture(HandleResult.OK);
            }
            return CompletableFuture.completedFuture(buildHr);
        }
        HandleResult hr = rdb.handleRequest(req.copy(buildHr.data), respData);
        return CompletableFuture.completedFuture(hr);
    }

    @Override
    protected String getDBConfig(Map<String, Object> cfg) {
        return DB;//接口定义中可以不必配置
    }
}