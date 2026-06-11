package cn.net.zhijian.mesh.frm.process;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 用于在客户端调用非USER授权的接口。
 * 接受端侧请求，并以当前服务的权限转交给目标服务，
 * 如果当前服务无权限，调用会失败。
 * 请求中必须携带_service、_url、_method三个参数，指定目标服务及url。
 * 其他参数，都原样透传给目标服务。
 * 此处理器，不能处理public请求，因为此类接口无需权限，不必通过此处理器
 * @author flyinmind of csdn.net
 *
 */
public class ProxyProcessor extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String CFG_ALLOWED = "allowed";

    private static final String PARA_SERVICE = "_service";
    private static final String PARA_URL = "_url";
    private static final String PARA_METHOD = "_method";
    
    private final Map<String, ScriptElement[]> allowed = new HashMap<>(); //容许调用的服务+接口列表

    public ProxyProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }
    
    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        Map<String, Object> params = req.params();

        String service = ValParser.getAsStr(params, PARA_SERVICE); //被调服务名
        String url = ValParser.getAsStr(params, PARA_URL).toLowerCase(); //被调服务URL
        int pos = url.indexOf('?');
        String k = service + '@' + (pos > 0 ? url.substring(0, pos) : url);
        k = k.toLowerCase();
        ScriptElement[] cfgParams = allowed.get(k);
        if(cfgParams == null) {
            LOG.debug("{} not allowed", k);
            return futureResult(RetCode.NO_RIGHT);
        }
        String method = ValParser.getAsStr(params, PARA_METHOD); //被调服方法，get/post

        Map<String, Object> extParams;
        String s = translateElements(cfgParams, req, resp);
        if(StringUtil.isEmpty(s) || (extParams = JsonUtil.jsonToMap(s)) == null) {
            return futureResult(HandleResult.InternalError);
        }
        ServiceInfo si = serviceInfo();

        if(method.equals("GET")) {
            url = UrlPathInfo.appendParas(url, extParams);
            return ServiceClient.getPrivate(new ServiceReqBuilder(si, service)
                    .url(url).traceId(req.traceId).cid(req.cid()));
        }
        
        if(method.equals("DELETE")) {
            url = UrlPathInfo.appendParas(url, extParams);
            return ServiceClient.deletePrivate(new ServiceReqBuilder(si, service)
                    .url(url).traceId(req.traceId).cid(req.cid()));
        }
        
        char ch;
        ServiceReqBuilder builder = new ServiceReqBuilder(si, service)
            .url(url)
            .traceId(req.traceId)
            .cid(req.cid());
        
        for(Map.Entry<String, Object> p : params.entrySet()) {
            k = p.getKey();
            ch = k.charAt(0);
            if(ch == '#' || ch == '_') {
                continue;//系统参数或者_service/_url/_method等参数，忽略掉
            }
            builder.put(k, p.getValue());
        }

        builder.putAll(extParams);
        if(method.equals("PUT")) {
            return ServiceClient.putPrivate(builder);
        }
        return ServiceClient.postPrivate(builder);
    }

    @Override
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        Map<String, Object> allowedCfg = ValParser.getAsObject(cfg, CFG_ALLOWED);
        //不容许不做任何限制，否则本服务有权后台调用的接口，通过此处，皆可调用
        if(allowedCfg == null || allowedCfg.isEmpty()) {
            LOG.error("There must be {} config", CFG_ALLOWED);
            return false;
        }
        /*
         * allowed:{
         *     "service_name1@url":{...},
         *     "service_name2@url":{...}
         * }
         */
        String s;
        ScriptElement[] params;
        Set<String> availableParas = availableParas(request);

        for(Map.Entry<String, Object> a : allowedCfg.entrySet()) {
            String allowedUrl = a.getKey().toLowerCase();
            Object o = a.getValue();
            if(o instanceof String) {
                s = (String)o;
            } else {
                Map<String, Object> m = ValParser.parseObject(o);
                if(m == null || m.isEmpty()) {
                    allowed.put(allowedUrl, null); //无参数，则定义一个空的map
                    continue;
                }
                s = JsonUtil.objToJson(m);
            }
            params = ScriptElement.parsePlaceHolder(s, availableParas, IConst.EMPTY_STR, null);
            allowed.put(allowedUrl, params);
        }

        return super.parse(url, cfg, request);
    }
}
