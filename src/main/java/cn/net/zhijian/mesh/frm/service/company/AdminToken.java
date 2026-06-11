package cn.net.zhijian.mesh.frm.service.company;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.ValParser;

/**
 * webdb备份时调用此接口，获得访问云侧company、本地keystore接口的admin token。
 * 直接使用本地CompanyInfo生成公司admintoken
 * `/company/api/webdb/admintoken`
 * @author flyinmind of csdn.net
 *
 */
public class AdminToken extends AbsProcessor {
    public AdminToken(ServiceInfo serviceInfo, ApiInfo apiInfo) {
        super(serviceInfo, apiInfo, "get_webdb_admin_token");
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        int cid = req.cid();
        CompanyInfo localCompany = CompanyInfo.instance();
        
        if(localCompany.id != cid) {//只能对本地公司执行命令
            return futureResult(RetCode.NO_RIGHT, "invalid company id " + cid);
        }

        List<String> services = ValParser.getAsStrList(req.params(), "services");
        Map<String, Object> data = new HashMap<>();
        if(services != null) {
            for (String s : services) {
                AccessToken token = localCompany.adminToken(s);
                data.put(s, token.generate());
            }
        }
        return futureResult(data);
    }
}
