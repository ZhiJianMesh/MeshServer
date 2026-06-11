package cn.net.zhijian.mesh.frm.service.aide;

import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;

/**
 * 每个服务都会自动添加本包中的接口
 * 实现初始化、数据库脚本执行等公共能力。
 * 服务的所有接口加载都成功后，会自动添加。
 * @author flyinmind of csdn.net
 *
 */
abstract class AideBase extends AbsProcessor {
    protected final IServiceServer server;
    
    public AideBase(IServiceServer vServer, ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
        this.server = vServer;
    }
 
    /**
     * 参数定义列表
     * @return 参数列表
     */
    public RequestInfo getRequestInfo() {
        return new RequestInfo();
    }
}