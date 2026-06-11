package cn.net.zhijian.mesh.frm.service.httpdns;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * 用在私有云中，
 * 此类的实现是不管传入列表是什么，全部返回
 * boot包中的类不涉及反射，所以不需要解决proguard问题，无需在AbstractProcessor中注册
 * `/httpdns/api/company/entrance`
 * @author flyinmind of csdn.net
 *
 */
public class Entrance extends AbsProcessor {
    public Entrance(ServiceInfo serviceInfo, ApiInfo apiInfo) {
        super(serviceInfo, apiInfo, "get_entrance");
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        //accecc_token传accessCode，与服务端httpdns中的是同一个，但是httpdns中是sha256后的code
        String token = req.header(HEAD_ACCESS_TOKEN);
        if(StringUtil.isEmpty(token)) {
            return futureResult(RetCode.INVALID_TOKEN);
        }
        int cid = req.cid();
        CompanyInfo localCompany = CompanyInfo.instance();
        if(localCompany.id != cid) {
            return futureResult(RetCode.NO_RIGHT, "invalid company id " + cid);
        }
        
        /*
         * accesscode注册到公有云httpdns时，经过了sha256，
         * 公有云无法知道accesscode原值，而entrance接口要发到公有云，
         * 所以，accesscode需要经过sha256运算，才能与公有云比对。
         */
        String ac = SecureUtil.sha256(localCompany.accessCode());
        if(!token.equals(ac)) {
            return futureResult(RetCode.INVALID_TOKEN);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("insideAddr", localCompany.insideAddr);
        data.put("outsideAddr", localCompany.outsideAddr());
        data.put("name", localCompany.name());
        return futureResult(data);
    }
}
