package cn.net.zhijian.mesh.frm.process;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.js.JsEngine;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 调用其他服务的接口
 * @author flyinmind of csdn.net
 *
 */
public class CallServiceProcessor extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    
    private static final String CS_CFG_CALLS = "calls";
    private static final String CS_CFG_SERVICE = "service";
    private static final String CS_CFG_URL = "url";
    private static final String CS_CFG_METHOD = "method";
    private static final String CS_CFG_PARAMETERS = "parameters";
    private static final String CS_CFG_HEADERS = "headers";
    private static final String CS_CFG_TOKENSIGN = "tokenSign";
    private static final String CS_CFG_TRANS = "trans"; //是否透传参数，只用于POST/PUT
    private static final String CS_CFG_ANY = "any"; //任何一个ok，最终都ok

    //post/put请求时，只支持json格式
    private enum CallMethod {GET, DELETE, POST, PUT}

    private CallInfo[] calls;
    private boolean any = false; //任何一个ok，最终都ok

    public CallServiceProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        if(calls.length == 1) {
            return calls[0].handle(req, resp);
        }

        List<HandleResult> results = new ArrayList<>();
        List<CompletableFuture<HandleResult>> actions = new ArrayList<>(calls.length);
        for(CallInfo ci : calls) { //多个call同时发出
            actions.add(ci.handle(req, resp).whenCompleteAsync((hr, e)-> {
                if(e != null) {
                    String service = translateElements(ci.service, req, resp);
                    String url = translateElements(ci.url, req, resp);
                    LOG.error("Fail to call {}.{}", service, url, e);
                    results.add(new HandleResult(RetCode.INTERNAL_ERROR, "fail to call " + service + '.' + url));
                } else {
                    results.add(hr);
                }
            }, Pool));
        }

        return CompletableFuture.allOf(actions.toArray(new CompletableFuture<?>[this.calls.length])).thenComposeAsync(_v -> {
            Map<String, Object> data = new HashMap<>();
            for(HandleResult hr : results) {
                if(hr.code != RetCode.OK && !this.any) {
                    return CompletableFuture.completedFuture(hr);
                }
                if(hr.data != null) {
                    data.putAll(hr.data);
                }
            }
            return futureResult(data);
        }, Pool);
    }


    /**
     * service:"webdb",
     * url:"request",
     * method:"post",
     * parameters: "{\"db\":@{db},\"sql\":\"select...\"}"
     */
    @Override
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        List<Object> callCfgs = ValParser.getAsList(cfg, CS_CFG_CALLS);
        if(callCfgs == null || callCfgs.isEmpty()) {
            callCfgs = new ArrayList<>();
            callCfgs.add(cfg); //没有calls配置项，则直接放在顶层配置中
        } else {
            this.any = ValParser.getAsBool(cfg, CS_CFG_ANY, this.any);
        }

        this.calls = new CallInfo[callCfgs.size()];
        Set<String> availableParas = availableParas(request);
        for(int i = 0; i < callCfgs.size(); i++) {
            Map<String, Object> one = ValParser.parseObject(callCfgs.get(i));
            CallInfo ci;
            if(one == null || one.isEmpty()
               || (ci = CallInfo.parse(cfg, one, availableParas)) == null) {
                LOG.error("Invalid call config@{} in {}", i, url.toString());
                return false;
            }
            this.calls[i] = ci;
        }
        return super.parse(url, cfg, request);
    }
    
    private static class CallInfo {
        private ScriptElement[] service;
        private ScriptElement[] url;
        private ScriptElement[] parameters = null;
        private ScriptElement[] headers = null;
        private CallMethod method;
        private boolean trans = false;
        private char urlAddChar = '?';
        private int signType = IOAuth.SIGNTYPE_NONE;
        
        public static CallInfo parse(Map<String, Object> cfg, Map<String, Object> callCfg, Set<String> availableParas) {
            CallInfo ci = new CallInfo();
            
            MapBuilder.putIfAbsent(cfg, callCfg); //将公共的复制到每个调用的配置中
            String s = ValParser.getAsStr(callCfg, CS_CFG_SERVICE);
            ci.service = ScriptElement.parsePlaceHolder(s, availableParas, "", null);
            if(ci.service == null || ci.service.length == 0) {
                LOG.error("Invalid call-service processor {}", CS_CFG_SERVICE);
                return null;
            }

            s = ValParser.getAsStr(callCfg, CS_CFG_URL);
            if(s.indexOf('?') > 0) {
                ci.urlAddChar = '&'; //解析时判断，免得运行时判断
            }
            ci.url = ScriptElement.parsePlaceHolder(s, availableParas, "", null);
            if(ci.url == null || ci.url.length == 0) {
                LOG.error("Invalid {} config in call process", CS_CFG_URL);
                return null;
            }

            String signType = ValParser.getAsStr(callCfg, CS_CFG_TOKENSIGN).toUpperCase();
            if(signType.equals(OM_TOKEN_CHECKER)) {
                ci.signType = SIGNTYPE_APPKEY;
            } else if(signType.equals(APP_TOKEN_CHECKER)) {
                ci.signType = SIGNTYPE_APPKEY;
            } else if(signType.equals(OAUTH_TOKEN_CHECKER)) {
                ci.signType = SIGNTYPE_CODEBOOK;
            }

            ci.trans = ValParser.getAsBool(callCfg, CS_CFG_TRANS, false);
            if(ci.trans) { //透明转发时，无需解析参数，所以无需解析parameters
                return ci;
            }

            //如果是透传，不在乎method，及参数格式
            String method = ValParser.getAsStr(callCfg, CS_CFG_METHOD).toUpperCase();
            if(StringUtil.isEmpty(method)) {
                LOG.error("Invalid call-service processor {}", CS_CFG_METHOD);
                return null;
            }

            //如果method是POST，parameters只接受json格式，所以需要对引号进行转换，而get无此必要
            String quote = "";
            String safeQuote = null;
            if(method.equals(IConst.METHOD_GET)) {
                ci.method = CallMethod.GET;
            } else if(method.equals(IConst.METHOD_DELETE)) {
                ci.method = CallMethod.DELETE;
            } else if(method.equals(IConst.METHOD_POST)) {
                ci.method = CallMethod.POST;
                quote = "\"";
                safeQuote = "\\\"";
            } else if(method.equals(IConst.METHOD_PUT)) {
                ci.method = CallMethod.PUT;
                quote = "\"";
                safeQuote = "\\\"";
            } else {
                LOG.error("Invalid call-service processor method {}", method);
                return null;
            }

            Object params = callCfg.get(CS_CFG_PARAMETERS);
            if(params == null) {
                if(ci.method == CallMethod.POST || ci.method == CallMethod.PUT) {
                    LOG.error("There must be `{}` in a post/put call", CS_CFG_PARAMETERS);
                    return null;
                }
                return ci;
            }
            
            String parameters;
            if(params instanceof Map) {
                parameters = JsonUtil.objToJson(params);
            } else {
                parameters = ValParser.parseString(params);
            }
            ci.parameters = ScriptElement.parsePlaceHolder(parameters, availableParas, quote, safeQuote);
            if(ci.parameters == null || ci.parameters.length == 0) {
                LOG.error("Invalid `{}`", CS_CFG_PARAMETERS);
                return null;
            }

            //请求其他服务时可以携带headers，headers不是必须的
            Object headers = callCfg.get(CS_CFG_HEADERS);
            if(headers == null) {
                return ci;
            }

            if(headers instanceof Map) {
                s = JsonUtil.objToJson(params);
                ci.headers = ScriptElement.parsePlaceHolder(s, availableParas, "\"", "\\\"");
            } else {
                s = ValParser.parseString(params);
                ci.headers = ScriptElement.parsePlaceHolder(s, availableParas, "", null);
            }
            if(ci.headers == null || ci.headers.length == 0) {
                LOG.error("Invalid `{}` {}", CS_CFG_HEADERS, headers);
                return null;
            }
            return ci;
        }
        
        protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
            ServiceInfo caller = req.serviceInfo();
            String callee = translateElements(this.service, req, resp);
            int cid = req.cid();
            ServiceReqBuilder callBuilder = new ServiceReqBuilder(caller, callee)
                    .traceId(req.traceId)
                    .cid(cid);
            if(this.signType == IOAuth.SIGNTYPE_NONE) {
                return handle(req, resp, callBuilder);
            }
            
            if(this.signType == IOAuth.SIGNTYPE_CODEBOOK) {
                ServiceReqBuilder tokenBuilder = new ServiceReqBuilder(caller, callee)
                        .traceId(req.traceId).cid(cid);
                return ServiceClient.serviceToken(tokenBuilder).thenComposeAsync((token) -> {
                    if (StringUtil.isEmpty(token)) {
                        LOG.error("Fail to get token {}/{} in {}", callee, url, caller);
                        return futureResult(RetCode.NO_RIGHT, "fail to get token");
                    }
                    callBuilder.token(token);
      
                    return handle(req, resp, callBuilder);
                }, Pool);
            }
            
            /*if(this.signType == IOAuth.SIGNTYPE_APPKEY){}*/
            /*
             * OM私钥也是一种特殊的应用私钥，所以两种生成token的方式相同，但是在被调方处理不同：
             *
             * 1）如果是SIGN_TYPE_APP_KEY，需要被调方预置该应用的公钥，比如同一服务内部互调；
             * 2）如果是SIGN_TYPE_OM_KEY，所有的应用都已经预置了OM的公钥，所以可以直接使用；
             * 3）service之间调用，需要相同partition，而OM调用不需要；
             *
             * OM方式请求不一定能通过，因为需要被调方接口定义中tokenChecker设为OMKEY，
             * 业务运维类接口，可以使用OMKEY。
             * 在VirtualServer中加载服务时，为所有服务都产生了tokenWorker
             */
            AccessToken accessToken = caller.tokenWorker.create(PartitionConfig.instance().partition,
                    caller.name, callee, AccessToken.EXT_FEATURE_ALL + cid, TOKENTYPE_SERVICE);
            callBuilder.token(accessToken.generate());
            return handle(req, resp, callBuilder);
        }        
        
        private CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp, ServiceReqBuilder builder) {
            String url = translateElements(this.url, req, resp);
            if(this.trans) { //post/put方式，直接透传请求体
                if(req.method.equals(IConst.METHOD_POST)) {
                    return ServiceClient.servicePost(builder.url(url).body(JsonUtil.objToJson(req.params())));
                }
                if(req.method.equals(IConst.METHOD_PUT)) {
                    return ServiceClient.servicePut(builder.url(url).body(JsonUtil.objToJson(req.params())));
                }
                
                UrlPathInfo urlInfo = new UrlPathInfo(url);
                urlInfo.appendParas(req.params());
                builder.url(urlInfo.toString());
                if(req.method.equals(IConst.METHOD_DELETE)) {
                    return ServiceClient.serviceDelete(builder);
                }
                return ServiceClient.serviceGet(builder);
            }

            if(this.headers != null) {
                String sHeaders = translateElements(this.headers, req, resp);
                builder.headers(JsonUtil.jsonToStrMap(sHeaders));
            }

            if(this.parameters != null) {
                String sParameters = translateElements(this.parameters, req, resp);
                if(sParameters.startsWith(JS_HEAD)) {
                    sParameters = JsEngine.getString(sParameters.substring(JS_HEAD.length()));
                }
                if(this.method == CallMethod.GET) {
                    return ServiceClient.serviceGet(builder.url(url + this.urlAddChar + sParameters));
                }
                if(this.method == CallMethod.DELETE) {
                    return ServiceClient.serviceDelete(builder.url(url + this.urlAddChar + sParameters));
                }
                if(this.method == CallMethod.PUT) {
                    return ServiceClient.servicePut(builder.url(url).body(sParameters));
                }
                return ServiceClient.servicePost(builder.url(url).body(sParameters));
            } else {
                builder.url(url);
                if(this.method == CallMethod.GET) {
                    return ServiceClient.serviceGet(builder);
                }
                if(this.method == CallMethod.DELETE) {
                    return ServiceClient.serviceDelete(builder);
                }
                if(this.method == CallMethod.PUT) {
                    return ServiceClient.servicePut(builder.body(IConst.EMPTY_STR));
                }
                return ServiceClient.servicePost(builder.body(IConst.EMPTY_STR));
            }
        }
    }
}
