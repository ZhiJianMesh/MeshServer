package cn.net.zhijian.mesh.frm.process;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * javascript处理类，负责js脚本的解释执行。
 * 系统中实现了部分原生接口
 * @author flyinmind of csdn.net
 *
 */
public class VarProcessor extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();

    private static final String CFG_VARS = "vars";
    
    private RequestInfo.Var[] vars;

    public VarProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        for(RequestInfo.Var v : vars) {
            v.run(req, resp);
         }
        return futureResult();
    }

    @Override
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        List<Object> varCfgs = ValParser.getAsList(cfg, CFG_VARS);
        if(varCfgs == null || varCfgs.isEmpty()) {
            LOG.error("config `{}` is null in {}", CFG_VARS, cfg);
            return false;
        }
        this.vars = request.parse(serviceInfo.name, apiInfo, varCfgs);
        for(RequestInfo.Var v : vars) {
            apiInfo.addExtPara(v.name);
        }
        return super.parse(url, cfg, request);
    }
}