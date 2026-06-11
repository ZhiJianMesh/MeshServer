package cn.net.zhijian.mesh.frm.process;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.js.JsEngine;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * javascript处理类，负责js脚本的解释执行。
 * 系统中实现了部分原生接口
 * @author flyinmind of csdn.net
 *
 */
public class JSProcessor extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String CFG_SCRIPT = "script";

    private ScriptElement[] scriptElements;

    public JSProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String script = translateElements(this.scriptElements, req, resp);
        if(script == null) {
            LOG.error("Invalid script {}, fail to compile with req", this.name());
            return futureResult(RetCode.INTERNAL_ERROR, "fail to compile js with req");
        }
        String s = script.trim();
        //js速度非常慢，所以这里即使做了多余判断，也远比使用js高效几个数量级
        HandleResult hr = HandleResult.tryParse(s);
        if(hr == null) { //直接返回结果
            hr = JsEngine.getHandleResult(s);
        }
        return CompletableFuture.completedFuture(hr);
    }

    @Override
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        String script = ValParser.getAsStr(cfg, CFG_SCRIPT);
        if(StringUtil.isEmpty(script)) {
            LOG.error("Invalid js processor script, {}", url.toString());
            return false;
        }
        
        this.scriptElements = ScriptElement.parsePlaceHolder(script, availableParas(request), "'", "'");
        if(this.scriptElements == null || this.scriptElements.length == 0) {
            LOG.error("Invalid js processor script after parsePlaceHolder, {}", url.toString());
            return false;
        }

        return super.parse(url, cfg, request);
    }
}