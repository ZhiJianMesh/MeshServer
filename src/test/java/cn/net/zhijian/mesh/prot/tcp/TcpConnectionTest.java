package cn.net.zhijian.mesh.prot.tcp;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.prot.http.NullServerRequest;
import cn.net.zhijian.mesh.prot.tcp.TcpConnection.ControlRequest;
import cn.net.zhijian.util.JsonUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * TcpConnection 单元测试。
 * 1) handleCtrlResp 接收端解析纯逻辑（不联网，ctx 传 null，注入 requests 后验证）；
 * 2) sendControl 帧结构序列化断言（用 EmbeddedChannel 捕获写出的 ByteBuf）。
 */
public class TcpConnectionTest extends UnitTestBase {
    private void injectRequest(TcpConnection conn, int reqId, CompletableFuture<HandleResult> future) throws Exception {
        Map<Integer, ControlRequest> requests = conn.requests;
        requests.put(reqId, new ControlRequest(future, null));
    }

    private ByteBuf newRespBuf(int statusCode, byte[] body) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(statusCode);
        buf.writeInt(0);
        if(body != null) {
            buf.writeInt(body.length);
            buf.writeBytes(body);
        } else {
            buf.writeInt(0);
        }
        return buf;
    }

    @Test
    public void testHandleCtrlRespOkWithBody() throws Exception {
        TcpConnection conn = new TcpConnection("r1", 1, null);
        CompletableFuture<HandleResult> future = new CompletableFuture<>();
        injectRequest(conn, 1, future);

        Map<String, Object> data = new HashMap<>();
        data.put("test", 1);
        byte[] body = JsonUtil.objToBytes(data);

        boolean ok = conn.handleCtrlResp(ITcpProtocol.CONTROL, 1, newRespBuf(200, body));
        assertTrue(ok);
        HandleResult hr = future.get();
        assertEquals(RetCode.OK, hr.code);
        assertEquals(1, hr.data.get("test"));
    }

    @Test
    public void testHandleCtrlRespOkNoBody() throws Exception {
        TcpConnection conn = new TcpConnection("r2", 1, null);
        CompletableFuture<HandleResult> future = new CompletableFuture<>();
        injectRequest(conn, 3, future);

        assertTrue(conn.handleCtrlResp(ITcpProtocol.CONTROL, 3, newRespBuf(200, null)));
        assertEquals(RetCode.OK, future.get().code);
    }

    @Test
    public void testHandleCtrlRespErrorStatus() throws Exception {
        TcpConnection conn = new TcpConnection("r3", 1, null);
        CompletableFuture<HandleResult> future = new CompletableFuture<>();
        injectRequest(conn, 2, future);

        assertFalse(conn.handleCtrlResp(ITcpProtocol.CONTROL, 2, newRespBuf(500, null)));
        assertEquals(RetCode.API_ERROR, future.get().code);
    }

    @Test
    public void testHandleCtrlRespUnknownReqId() {
        TcpConnection conn = new TcpConnection("r4", 1, null);
        //未注册任何 reqId，应返回 false 且不抛异常
        assertFalse(conn.handleCtrlResp(ITcpProtocol.CONTROL, 99, newRespBuf(200, null)));
    }

    /**
     * sendControl 帧结构序列化断言：用 EmbeddedChannel 捕获写出到对端的 ByteBuf，
     * 验证帧头（total_len + cmd + reqId + val）+ headers/body 内容，以及请求先注册后发送。
     */
    @Test
    public void testSendControlFrameStructure() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ChannelHandlerContext ctx = ch.pipeline().firstContext();
        TcpConnection conn = new TcpConnection("r5", 1, ctx);

        ServiceInfo si = new ServiceInfo("home", "test");
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("X-Test", "1");
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("k", "v");
        AbsServerRequest req = new NullServerRequest("GET", "/test", headers, body, si, null);

        CompletableFuture<HandleResult> future = conn.sendControl(req, ITcpProtocol.CONTROL);

        ByteBuf out = (ByteBuf) ch.readOutbound();
        assertNotNull(out);
        int totalLen = out.readInt();
        byte frameCmd = out.readByte();   //帧内 cmd 字节固定为 CONTROL
        int reqId = out.readInt();
        int val = out.readInt();          //传入的 cmd 参数落在 val 位置
        int headLen = out.readInt();
        byte[] h = new byte[headLen];
        out.readBytes(h);
        int bodyLen = out.readInt();
        byte[] b = new byte[bodyLen];
        out.readBytes(b);

        assertEquals(ITcpProtocol.CONTROL, frameCmd);
        assertEquals(ITcpProtocol.CONTROL, val);
        assertEquals(totalLen, 1 + 4 + 4 + 4 + headLen + 4 + bodyLen);
        assertTrue(reqId >= 1);
        //headers/body 反序列化内容与传入一致
        Map<String, Object> hMap = JsonUtil.jsonToMap(h);
        Map<String, Object> bMap = JsonUtil.jsonToMap(b);
        assertEquals("1", hMap.get("X-Test"));
        assertEquals("v", bMap.get("k"));
        //future 尚未完成（等待对端响应），证明请求已先登记到内部 requests
        assertFalse(future.isDone());

        out.release();
        ch.close();
    }
}
