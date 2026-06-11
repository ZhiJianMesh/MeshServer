package cn.net.zhijian.mesh.frm.process;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.mesh.js.JsEngine;
import cn.net.zhijian.util.Calculator;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 逻辑表达式处理类，负责逻辑表达式的解释执行。
 * 要求表达式中只能用@{CONDITION}，并且不配置返回值，使用默认的1或0
 * @author flyinmind of csdn.net
 *
 */
public final class LogicProcessor extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();

    private static final String CFG_CONDITION = "condition";
    private static final String CFG_SUCCESS = "success";
    
    private ScriptElement[] condition;
    private HandleResult errorResult;
    private ScriptElement[] successResult = null;
    
    public LogicProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String condition = translateElements(this.condition, req, resp);
        if(condition == null) {
            LOG.error("Invalid expressions {}, fail to compile with req", this.name());
            return futureResult(RetCode.INTERNAL_ERROR, "fail to compile logic expression with req");
        }
        double v = Calculator.calculate(condition);
        if(v != 0) {
            if(this.successResult == null) {
                return CompletableFuture.completedFuture(HandleResult.OK);
            }
            String dataStr = translateElements(this.successResult, req, resp);
            if(dataStr.startsWith(JS_HEAD)) {
                dataStr = JsEngine.getString(dataStr.substring(JS_HEAD.length()));
            }
            Map<String, Object> data = JsonUtil.jsonToMap(dataStr);
            return CompletableFuture.completedFuture(new HandleResult(data));
        }
        return CompletableFuture.completedFuture(errorResult);
    }

    @Override
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        String s = ValParser.getAsStr(cfg, CFG_CONDITION);
        if(StringUtil.isEmpty(s)) {
            LOG.error("No logic expression, {}", url.toString());
            return false;
        }
        //将逻辑表达式变成数学算式
        String formula = s.replace("&&", "*").replace("||", "+");
        Set<String> availableParas = availableParas(request);
        this.condition = ScriptElement.parsePlaceHolder(formula, availableParas, "\"", "\\\"");
        if(this.condition == null || this.condition.length == 0) {
            LOG.error("Invalid logic expression after parsePlaceHolder, {}", url.toString());
            return false;
        }
        int code = RetCode.parseCode(cfg.get(CFG_ERROR_CODE), RetCode.LOGIC_FALSE);
        String info = ValParser.getAsStr(cfg, CFG_ERROR_INFO, RetCode.getInfo(code));
        this.errorResult = new HandleResult(code, info);
        
        Object success = cfg.get(CFG_SUCCESS);
        if(success != null) {
            String dataStr;
            if(success instanceof Map) {
                dataStr = JsonUtil.objToJson(success);
            } else {
                dataStr = ValParser.parseString(success);
            }
            this.successResult = ScriptElement.parsePlaceHolder(dataStr, availableParas, "\"", "\\\"");
            if(this.successResult == null || this.successResult.length == 0) {
                LOG.error("Invalid `{}` in {}", CFG_SUCCESS, url);
                return false;
            }
        }        
        
        return super.parse(url, cfg, request);
    }
}