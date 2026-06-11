package cn.net.zhijian.mesh.prot.http;

import java.util.Map;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import io.netty.handler.ssl.ApplicationProtocolNames;

/**
 * 解析后的http服务器端请求体
 * @author flyinmind of csdn.net
 *
 */
public class NullServerRequest extends AbsServerRequest {
    public NullServerRequest(String method, String uri,
        Map<String, String> headers, Map<String, Object> params,
        ServiceInfo si, AccessToken token) {
        super(null, method, uri, ApplicationProtocolNames.HTTP_1_1, headers, params, null);
        this.setServiceInfo(si);
        this.setToken(token);
    }
    /**
     * 只是更换掉参数，其他都保持不变。用于复制请求
     * @param params 请求参数
     * @return 请求对象
     */
    @Override
    public NullServerRequest copy(Map<String, Object> params) {
        return new NullServerRequest(this.method, this.uri,
                this.headers, params, this.si, this.token);
    }

    @Override
    public AbsServerResponse createResponse() {
        AbsServerResponse resp = new NullServerResponse(this);
        resp.setServiceInfo(this.si);
        return resp;
    }
    
    @Override
    public boolean isExternal() {
        return false;
    }
    
    @Override
    public String remoteAddr() {
        return requestAddr();
    }
}
