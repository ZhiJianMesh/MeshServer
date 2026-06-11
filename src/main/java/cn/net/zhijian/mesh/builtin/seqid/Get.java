package cn.net.zhijian.mesh.builtin.seqid;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.process.RDBProcessor;
import cn.net.zhijian.util.ValParser;

/**
 * 获得增长序列中的一段编号，此段编号可以保证与其他实例或线程中获取的是不一样的。
 * 唯一性通过类似java AtomicInteger的方式实现：
 * 先获得cur（当前的编号），然后将cur加上step存入数据库，
 * 在更新时判断了cur还是不是当前线程查询时的cur，如果不是则不更新，
 * 此时数据库更新的行数为0，在此类中，会重新执行以此，直到cur与先前取得的cur是一致的为止
 */
public class Get extends RDBProcessor {
    private static final String UPDATE_LINE_NUM = "upd" + AbsRDBWorker.HANDLE_RESULT;

    public Get(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        return super.handle(req, resp).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                return CompletableFuture.completedFuture(hr);
            }
            int updNum = ValParser.getAsInt(hr.data, UPDATE_LINE_NUM);
            //类似atomicinteger的实现，只有更新成功才算成功
            //更新的sql中检查了更新前的值是否为本次获取的值
            if(updNum > 0) {
                return CompletableFuture.completedFuture(hr);
            }
            return this.handle(req, resp); //重新处理
        }, Pool);
    }
}
