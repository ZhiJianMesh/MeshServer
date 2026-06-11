package cn.net.zhijian.mesh.frm.config;

import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.server.TcpChannel.ITcpResourceChecker;

public class TestTcpChecker implements ITcpResourceChecker {
    @Override
    public CompletableFuture<HandleResult> check(int cid, String resource, String pwd, String ip) {
        return HandleResult.future();
    }
}
