package cn.net.zhijian.mesh.frm.method;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.Transcoder;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ProcessInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ResponseInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IProcessor;
import io.netty.buffer.ByteBuf;
import cn.net.zhijian.mesh.prot.http.NullServerRequest;
import cn.net.zhijian.mesh.prot.http.NullServerResponse;
import cn.net.zhijian.util.UrlPathInfo;

/**
 * ApiMethod 调度编排单元测试（mock processor）。
 * 验证：process 内多 processor 顺序执行；某 processor 失败时后续不执行；
 * head 阶段失败时跳过 process 阶段。
 * 使用轻量 mock（匿名实现 IProcessor）替代真实 processor。
 */
public class ApiMethodTest extends UnitTestBase {

    private final ServiceInfo si = new ServiceInfo("home", "test");

    /** 捕获最终响应的轻量 mock：成功路径与失败路径都会触发 done。 */
    private static class CapturingResponse extends NullServerResponse {
        volatile int endCode = -1;
        final CountDownLatch done = new CountDownLatch(1);
        CapturingResponse(AbsServerRequest req) {
            super(req);
        }
        @Override
        public void end(HandleResult hr) {
            endCode = hr.code;
            done.countDown();
        }
        @Override
        public void end(ByteBuf body, int len) {
            endCode = RetCode.OK;
            done.countDown();
        }
    }

    private IProcessor mockProcessor(String name, List<String> order, int resultCode) {
        return new IProcessor() {
            public Transcoder trans(int code) {
                return null;
            }
            public String name() {
                return name;
            }
            public ServiceInfo serviceInfo() {
                return si;
            }
            public CompletableFuture<HandleResult> handleAll(AbsServerRequest req, Map<String, Object> resp) {
                order.add(name);
                return CompletableFuture.completedFuture(new HandleResult(resultCode));
            }
        };
    }

    private ApiMethod buildApiMethod(ProcessInfo process, ProcessInfo head) {
        ApiInfo apiInfo = new ApiInfo("test", IConst.METHOD_GET, new UrlPathInfo("/test"), null, false);
        return new ApiMethod(si, apiInfo, new RequestInfo(), new ResponseInfo(null),
                process, head, null, null, null, null);
    }

    private AbsServerRequest newReq() {
        return new NullServerRequest("GET", "/test", new HashMap<>(), new HashMap<>(), si, null);
    }

    @Test
    public void testProcessOrder() throws Exception {
        List<String> order = new ArrayList<>();
        ProcessInfo process = new ProcessInfo(new IProcessor[]{
                mockProcessor("p1", order, RetCode.OK),
                mockProcessor("p2", order, RetCode.OK),
                mockProcessor("p3", order, RetCode.OK)});
        ApiMethod m = buildApiMethod(process, null);

        CapturingResponse resp = new CapturingResponse(newReq());
        m.execute(newReq(), resp);
        assertTrue(resp.done.await(5, TimeUnit.SECONDS));
        assertEquals(RetCode.OK, resp.endCode);
        assertEquals(Arrays.asList("p1", "p2", "p3"), order);
    }

    @Test
    public void testProcessFailStopsSubsequent() throws Exception {
        List<String> order = new ArrayList<>();
        ProcessInfo process = new ProcessInfo(new IProcessor[]{
                mockProcessor("p1", order, RetCode.OK),
                mockProcessor("p2", order, RetCode.API_ERROR),
                mockProcessor("p3", order, RetCode.OK)});
        ApiMethod m = buildApiMethod(process, null);

        CapturingResponse resp = new CapturingResponse(newReq());
        m.execute(newReq(), resp);
        assertTrue(resp.done.await(5, TimeUnit.SECONDS));
        assertEquals(RetCode.API_ERROR, resp.endCode);
        //p2 失败，p3 不应被调用
        assertEquals(Arrays.asList("p1", "p2"), order);
    }

    @Test
    public void testHeadFailSkipsProcess() throws Exception {
        List<String> order = new ArrayList<>();
        ProcessInfo head = new ProcessInfo(mockProcessor("h1", order, RetCode.API_ERROR));
        ProcessInfo process = new ProcessInfo(mockProcessor("p1", order, RetCode.OK));
        ApiMethod m = buildApiMethod(process, head);

        CapturingResponse resp = new CapturingResponse(newReq());
        m.execute(newReq(), resp);
        assertTrue(resp.done.await(5, TimeUnit.SECONDS));
        assertEquals(RetCode.API_ERROR, resp.endCode);
        //head 失败，process 不应执行
        assertEquals(Arrays.asList("h1"), order);
    }
}
