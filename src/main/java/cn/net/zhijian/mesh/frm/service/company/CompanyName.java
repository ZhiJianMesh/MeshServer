package cn.net.zhijian.mesh.frm.service.company;

import java.io.File;
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
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * 查询公司名称及logo，兼容至简网格云侧company的/name接口
 * @author flyinmind of csdn.net
 * `/company/api/name`
 */
public class CompanyName extends AbsProcessor {
    public static final String EMPTY_LOGO = "data:image/png;base64,aWNv";
    private final File logoFile;
    private String logo = null;
    
    public CompanyName(ServiceInfo serviceInfo, ApiInfo apiInfo, String logoFile) {
        super(serviceInfo, apiInfo, "get_company_name_logo");
        this.logoFile = new File(logoFile);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        int cid = req.cid();
        CompanyInfo localCompany = CompanyInfo.instance();
        
        if(localCompany.id != cid) {//只能对本地公司执行命令
            return futureResult(RetCode.NO_RIGHT, "invalid company id " + cid);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("name", localCompany.name());
        if(this.logo == null) {
            if(logoFile.exists()) {
                byte[] content = FileUtil.readFile(logoFile, false);
                this.logo = new String(content);
            }
        }
        data.put("logo", StringUtil.isEmpty(this.logo) ? EMPTY_LOGO : this.logo);
        return CompletableFuture.completedFuture(new HandleResult(RetCode.OK, data));
    }
}
