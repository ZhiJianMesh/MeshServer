package cn.net.zhijian.mesh.frm.process;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.server.TcpChannel;

/**
 * 接受客户端的http请求，转发给用TCP长连接服务器的客户端
 */
public class HttpXTcpProcessor extends AbsProcessor {
    private static TcpChannel tcpChannel = null;
    
    public HttpXTcpProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        if(tcpChannel == null) {
            return CompletableFuture.completedFuture(new HandleResult(RetCode.INVALID_STATE, "tcp server not initialized"));
        }
        return tcpChannel.sendControl(req);
    }
    
    /**
     * 设置tcpServer，在Launcher中，启动tcpServer之后调用
     * @param tcpServer tcp服务器
     */
    public static void setTcpChannel(TcpChannel tcpServer) {
        HttpXTcpProcessor.tcpChannel = tcpServer;
    }
}
