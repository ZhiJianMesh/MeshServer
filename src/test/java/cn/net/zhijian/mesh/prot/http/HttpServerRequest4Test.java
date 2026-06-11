package cn.net.zhijian.mesh.prot.http;

import java.util.Map;

import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import io.netty.handler.ssl.ApplicationProtocolNames;

public class HttpServerRequest4Test {
    /**
     * 只是为了方便单元测试，不可以在程序中使用
     * @param params 请求参数
     */
    public static Http1ServerRequest create(Map<String, String> headers, Map<String, Object> params) {
        return new Http1ServerRequest(null, IConst.METHOD_POST, "url",
                ApplicationProtocolNames.HTTP_1_1, headers, params, null, null, null, "test", 1);
    }
    
    public static Http1ServerRequest create(ServiceInfo si, Map<String, String> headers, Map<String, Object> params) {
        return new Http1ServerRequest(null, IConst.METHOD_POST, "url",
                ApplicationProtocolNames.HTTP_1_1, headers, params, null, si, null, "test", 1);
    }
}
