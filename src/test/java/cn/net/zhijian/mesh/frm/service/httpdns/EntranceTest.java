package cn.net.zhijian.mesh.frm.service.httpdns;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.prot.http.HttpServerRequest4Test;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

public class EntranceTest extends UnitTestBase implements IConst {
    @Test
    public void testPrivateEntrance() throws InterruptedException {
        CompanyInfo ci = CompanyInfo.instance();
        assertNotNull(ci);
        
        //入口查询：/service/api/entrance
        //在ServiceClient中，如果一个节点故障，会定期调用此接口探测节点是否恢复
        UrlPathInfo url = new UrlPathInfo(testService.name).push("test").push("entrance");
        ApiInfo apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, false);
        Entrance entrance = new Entrance(testService, apiInfo);
        String tk = SecureUtil.sha256(ci.accessCode());
        Map<String, String> headers = MapBuilder.of(HEAD_CID, "" + ci.id, HEAD_ACCESS_TOKEN, tk);
        Map<String, Object> params = MapBuilder.of("service", testService.name);
        AbsServerRequest req = HttpServerRequest4Test.create(testService, headers, params);
        CountDownLatch counter = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);
        
        entrance.handle(req, null).whenCompleteAsync((hr, e) -> {
            if(e == null && hr != null && hr.code == RetCode.OK) {
                String inside = ValParser.getAsStr(hr.data, "insideAddr");
                result.set(ci.insideAddr.equals(inside));
                System.out.println("ci.insideAddr:" + ci.insideAddr + ",inside:" + inside);
            }
            counter.countDown();
        }, IThreadPool.Pool);
        counter.await();
        assertTrue(result.get());
    }
}
