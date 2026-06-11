package cn.net.zhijian.mesh.frm.service.company;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 端侧提供公司密码，获得访问云侧接口的company token。
 * 这类接口通常用于管理公司的相关信息。
 * 私有云接到请求后，将密码传递到云侧，云侧校验密码，
 * 成功后，私有云才会使用本地的私钥生成company token
 * `/company/api/token`
 * 
 * @author flyinmind of csdn.net
 *
 */
public class CompanyToken extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    
    public CompanyToken(ServiceInfo serviceInfo, ApiInfo apiInfo) {
        super(serviceInfo, apiInfo, "get_company_token");
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        int cid = req.cid();
        CompanyInfo localCompany = CompanyInfo.instance();
        
        if(localCompany.id != cid) {//只能对本地公司执行命令
            return futureResult(RetCode.NO_RIGHT, "invalid company id " + cid);
        }


        return localCompany.verifyPwd(req.getString("pwd")).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to verify password,result:{}", hr.brief());
                return hr;
            }
            List<String> services = ValParser.getAsStrList(req.params(), "services");
            Map<String, Object> data = new HashMap<>();
            if(services != null) {
                for (String s : services) {
                    AccessToken token = localCompany.adminToken(s);
                    data.put(s, token.generate());
                }
            }
            return new HandleResult(RetCode.OK, data);
        }, Pool);
    }
}
