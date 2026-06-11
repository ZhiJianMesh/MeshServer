package cn.net.zhijian.mesh.frm.service.company;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.para.ParameterInfo;

/**
 * 在服务器上执行sql，
 * 此接口由端侧发起，比如超级管理员在设置-高级-维护工具-数据库操作中发起，
 * 也可以由至简的远程维护人员在远程协助-数据库操作中发起，
 * 调用时需要提供由公司的密钥对签名的token，或者临时接入码签名的token，
 * token检查是BACKEND；本质是调用服务的sqlexec，
 * 此接口在`cn.net.zhijian.mesh.frm.service.aide.SqlExecutor`中实现，
 * 在VirtualServer启动时，会自动为每个服务添加此接口
 * `/company/api/sqlexec`
 * @author flyinmind of csdn.net
 *
 */
public class SqlExecute extends AbsProcessor {
    public SqlExecute(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }
    
    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        int cid = req.cid();
        CompanyInfo localCompany = CompanyInfo.instance();
        
        if(localCompany.id != cid) {//只能对本地公司执行命令
            return futureResult(RetCode.NO_RIGHT, "invalid company id " + cid);
        }
        
        String service = req.getString("service");
        ServiceReqBuilder builder = new ServiceReqBuilder(serviceInfo, service)
                //必须有om私钥才可以调用，所以只能用在单例服务器上
                .token(localCompany.adminToken(service).generate())
                .url("/sqlexec") //此接口在单例服务器启动时自动添加，调用SqlExecutor执行sql
                .cid(cid)
                .traceId(serviceInfo.name)
                .putAll(req.params());
        
        return ServiceClient.servicePost(builder);
    }
    
    public static RequestInfo getRequestInfo() {
        ParameterInfo sql = new ParameterInfo.Builder("sql", ParameterInfo.TYPE_STRING)
                .setMax(1000).setMin(8).build();
        ParameterInfo db = new ParameterInfo.Builder("db", ParameterInfo.TYPE_STRING)
                .setRegular("^[a-zA-Z0-9_]{1,30}$").build();
        ParameterInfo service = new ParameterInfo.Builder("service", ParameterInfo.TYPE_STRING)
                .setRegular("^[a-zA-Z0-9_]{1,30}$").build();
        
        return new RequestInfo(new ParameterInfo[] {service, db, sql});
    }
}