package cn.net.zhijian.mesh.frm.service.aide;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.prot.http.HttpServerRequest4Test;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.UrlPathInfo;

public class DefaultCheckupTest extends UnitTestBase implements IConst {
    @Test
    public void testDefaultCheckup() throws InterruptedException {
        //保活探测：/service/api/checkup
        //在ServiceClient中，如果一个节点故障，会定期调用此接口探测节点是否恢复
        UrlPathInfo url = new UrlPathInfo(testService.name).push("test").push(API_CHECKUP);
        ApiInfo apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, false);
        DefaultCheckUp dcu = new DefaultCheckUp(testService, apiInfo);
        Map<String, String> headers = MapBuilder.of(IConst.HEAD_CID, "40");
        AbsServerRequest req = HttpServerRequest4Test.create(testService, headers, new HashMap<>());
        CountDownLatch counter = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);
        
        dcu.handle(req, new HashMap<>()).whenCompleteAsync((hr, e) -> {
            result.set(e == null && hr != null && hr.code == RetCode.OK
                    && hr.data.containsKey("cpuTime")
                    && hr.data.containsKey("reqTimes"));
            counter.countDown();
        }, IThreadPool.Pool);
        counter.await();
        assertTrue(result.get());
    }
}
