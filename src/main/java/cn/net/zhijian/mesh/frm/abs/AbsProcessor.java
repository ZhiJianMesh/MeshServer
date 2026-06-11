package cn.net.zhijian.mesh.frm.abs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.Transcoder;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IProcessor;
import cn.net.zhijian.mesh.frm.process.*;
import cn.net.zhijian.mesh.js.JsEngine;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.Calculator;
import cn.net.zhijian.util.FifoCache;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 处理器抽象类，实现处理结果的缓存等功能
 * @author flyinmind of csdn.net
 *
 */
public abstract class AbsProcessor implements IProcessor, IOAuth {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String PARA_USE_CACHE = "useCache";
    private static final String CFG_CACHE = "cache";
    protected static final String CFG_WHEN = "when";
    //多个处理中，在某个处理中，将一部分错误码转换成其他返回码
    private static final String CFG_CONVERT = "convert";
    private static final String CFG_NAMESPACE = "nameSpace"; //在返回字段名称前面加前缀
    private static final String CFG_ONSUCCESS = "onSuccess";
    
    //既有预置的类型，比如RDB\SEARCH\TREEDB\CALL\JS\PROXY\STATIC等
    //也有继承了AbstractProcessor的子类的完整类名称及对应的类
    //比如builtin下的各种java实现的接口处理类
    private static final Map<String, Class<? extends AbsProcessor>> Processors = new HashMap<>();

    static {
        register(TYPE_JAVA, AbsProcessor.class);
        register(TYPE_RDB, RDBProcessor.class);
        register(TYPE_TREEDB, TreeDBProcessor.class);
        register(TYPE_SEARCH, SearchDBProcessor.class);
        register(TYPE_LOC_RDB, LocRDBProcessor.class);
        register(TYPE_LOC_TREEDB, LocTreeDBProcessor.class);
        register(TYPE_LOC_SEARCH, LocSearchDBProcessor.class);
        register(TYPE_CALL, CallServiceProcessor.class);
        register(TYPE_PROXY, ProxyProcessor.class);
        register(TYPE_STATIC, StaticProcessor.class);
        register(TYPE_UPLOAD, UploadProcessor.class);
        register(TYPE_DOWNLOAD, DownloadProcessor.class);
        register(TYPE_JS, JSProcessor.class);
        register(TYPE_VAR, VarProcessor.class);
        register(TYPE_DATAEXISTS, DataExists.class);
        register(TYPE_LOGIC, LogicProcessor.class);
        register(TYPE_TCPX, HttpXTcpProcessor.class);
    }
    
    /**
     * 处理请求，只做最核心的正常处理，可以多个处理串联起来。
     * cache、onSuccess、trans等放在handleAll实现
     * @param req 从请前方传递来的参数
     * @param respData  前面的handle返回的结果全部集中到resp中。
     * @return 异步处理结果
     */
    protected abstract CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData);

    //processor响应的缓存
    protected static final FifoCache<String, CompletableFuture<HandleResult>> CachedResults = new FifoCache<>(
            10 * 60, //10分钟
            20000);
    
    protected final ServiceInfo serviceInfo;
    protected final String processName;
    protected final ApiInfo apiInfo;
    private final String baseCacheId; //用于加速cacheid的计算

    private Transcoder[] trans = null; //可以忽略的错误码
    //返回结果集时，如果设置了名称空间，则每个字段名签名都会增加它，以此避免名称空间冲突
    private String nameSpace = null;
    private OnSuccess onSuccess = null;//所有call执行完毕后的逻辑，可以不设置

    protected ScriptElement[] cache = null; //指定cache的key
    //判断在什么情况下需要执行，返回一个bool值，如果false，则不执行
    private ScriptElement[] when = null;

    /**
     * 解析processor公共的配置
     * @param url 接口url
     * @param cfg 配置
     * @param request 请求
     * @return 如果有配置错误，则返回false
     */
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        Set<String> allParas = availableParas(request);
        String s = ValParser.getAsStr(cfg, CFG_CACHE).trim();
        if(!StringUtil.isEmpty(s)) { //不缓存则可以不指定
            this.cache = ScriptElement.parsePlaceHolder(s, allParas, IConst.EMPTY_STR, null);
            if(this.cache == null || this.cache.length == 0) {
                LOG.error("Fail to parse 'cache' {} under {}", CFG_CACHE, url.toString());
                return false;
            }
        }

        s = ValParser.getAsStr(cfg, CFG_WHEN).trim();
        if(!StringUtil.isEmpty(s)) { //不判断，则可以不指定，是一段逻辑运算，返回true或false
            String formula = s.replace("&&", "*").replace("||", "+"); //变成数学算式
            this.when = ScriptElement.parsePlaceHolder(formula, allParas, IConst.EMPTY_STR, null);
            if(this.when == null || this.when.length == 0) {
                LOG.error("Fail to parse 'when' {} under {}", CFG_WHEN, url.toString());
                return false;
            }
        }
        
        s = ValParser.getAsStr(cfg, CFG_NAMESPACE).trim();
        if(!StringUtil.isEmpty(s)) {
            this.nameSpace = s;
        }
        
        Object tranObj = cfg.get(CFG_CONVERT);
        if(tranObj != null) { //错误码转换配置，可以配置一个map，也可以配置一个map数组
            List<Object> transCfg;
            if(tranObj instanceof List) {
                transCfg = ValParser.parseList(tranObj);
            } else if(tranObj instanceof Map){
                transCfg = new ArrayList<>();
                transCfg.add(tranObj);
            } else {
                LOG.error("Invalid {} config in process {}", CFG_CONVERT, name());
                return false;
            }
            if(transCfg != null && !transCfg.isEmpty()) {
                this.trans = Transcoder.parse(transCfg, allParas);
                if(this.trans == null) {
                    LOG.error("Fail to parse 'convert' {} under {}", CFG_CONVERT, url.toString());
                    return false;
                }
            }
        }
        
        Object o = cfg.get(CFG_ONSUCCESS);
        if(o == null) {
            LOG.debug("{}({}).{}=>when:{},convert:{},onSuccess:false",
                url, nameSpace, processName, when != null, trans != null);
            return true; //onSuccess可以不配置
        }
        LOG.debug("{}({}).{}=>when:{},convert:{},onSuccess:true",
            url, nameSpace, processName, when != null, trans != null);
        this.onSuccess = OnSuccess.parse(o, availableParas(request));
        if(this.onSuccess == null) {
            LOG.error("Invalid '{}' when parsing in `{}`", CFG_ONSUCCESS, url.toString());
            return false;
        }
        
        return true;
    }

    /**
     * 返回请求中可以使用哪些参数，主要用于解析时判断参数占位符是否存在。
     * 因为只用在启动后的解析过程中，此处并未做性能优化。去此处只能返回请求参数、变量与内置参数
     * @param request 请求
     * @return 所有可用的参数，包括系统参数，比如#reqAt,#tokenCaller,#cid,#partition等
     */
    protected Set<String> availableParas(RequestInfo request) {
        Set<String> availableParas = apiInfo.availableSysParas();
        availableParas.addAll(request.params());
        return availableParas;
    }
    
    public AbsProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        this.serviceInfo = serviceInfo;
        this.apiInfo = apiInfo;
        this.processName = processName;
        long id = StringUtil.longHashCode(serviceInfo.name, apiInfo.url, processName);
        this.baseCacheId = ByteUtil.long2Base64(id, Long.SIZE, false);
    }
    
    @Override
    public Transcoder trans(int code) {
        for(Transcoder c : this.trans) {
            if(c.in(code)) {
                return c;
            }
        }

        return null;
    }
    
    @Override
    public String name() {
        return processName;
    }
    
    @Override
    public ServiceInfo serviceInfo() {
        return serviceInfo;
    }
    
    protected CompletableFuture<HandleResult> readCache(AbsServerRequest req, Map<String, Object> resp) {
        String cacheId = this.baseCacheId + translateElements(this.cache, req, resp);
        //此处如果使用putIfAbsent(cacheId,handle(req,resp))
        //会导致cache失效，因为handle每次都会实际执行一次，虽然最终返回的可能是缓存的结果
        synchronized(CachedResults) {
            CompletableFuture<HandleResult> hr = CachedResults.get(cacheId);//get要放在同步中
            if(hr != null) {
                return hr;
            }
            hr = handle(req, resp);
            CachedResults.put(cacheId, hr);
            return hr;
        }
    }

    protected boolean useCache() {
        return cache != null;
    }

    /**
     * 通过前面process运行的结果，判断当前步骤是否需要执行
     */
    protected static boolean needRun(AbsServerRequest req, Map<String, Object> respData, ScriptElement[] when) {
        if(when == null || when.length == 0) {
            return true;
        }
        if(when.length == 1) { //大部分情况只有一个，直接运行condition
            return ValParser.parseBool(when[0].run(req, respData), true);
        }
        String formula = translateElements(when, req, respData);
        if(StringUtil.isEmpty(formula)) {
            return false;
        }
        return Calculator.calculate(formula) > 0.0; //js执行太慢，所以when不使用js
    }
    
    @Override
    public CompletableFuture<HandleResult> handleAll(AbsServerRequest req, Map<String, Object> respData) {
        /*
         * 根据前面的运行结果，判断是否需要运行；
         * 无需运行，则直接返回OK;
         * needRun是一段返回boolean的js脚本，通常是一个逻辑运算表达式
         */                
        if(!needRun(req, respData, this.when)) {
            LOG.debug("{}.{}\t{},needn't run `{}`", serviceInfo.name, req.uri, req.traceId, processName);
            return HandleResult.future();
        }

        CompletableFuture<HandleResult> cf;
        if(useCache() && req.getBool(PARA_USE_CACHE, true)) {
            //此处发生过错误，导致使用cache的情况，nameSpace不起作用，因为cache没有后继处理
            cf = readCache(req, respData);
        } else {
            cf = handle(req, respData);
        }
        if(LOG.isDebugEnabled()) {
            LOG.debug("{}.{}\t{},run `{}`", serviceInfo.name, req.uri, req.traceId, processName);
        }
        
        return cf.thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK) {
                if(LOG.isDebugEnabled()) {
                    LOG.debug("{}\t{},handleAll `{}`,result:{}",
                            req.uri, req.traceId, processName, hr.brief());
                }

                //即使转换了返回码，记录的结果仍然是转换前的
                req.put(IConst.EMBEDED_CODE, hr.code);
                req.put(IConst.EMBEDED_INFO, hr.info);
                req.put(IConst.EMBEDED_ERROR_HANDLE, name());//可用在onFinish中做判断

                if(this.trans == null) {
                    return hr;
                }
                Transcoder trans = trans(hr.code);
                if(trans == null) { //不可容忍失败，则立刻结束
                    return hr;
                }
    
                //如果能转码，则返回转码后的内容
                if(trans.to != RetCode.OK) {
                    return new HandleResult(trans.to, trans.info);
                }

                if(trans.data != null) { //如果转为ok，可以预置返回内容
                    String json = ScriptElement.runAll(trans.data, req, respData);
                    Map<String, Object> data;
                    if(StringUtil.isEmpty(json) || (data = JsonUtil.jsonToMap(json)) == null) {
                        return new HandleResult(trans.to, trans.info);
                    }
                    saveData(nameSpace, data, respData);
                }
            } else {
                if(LOG.isDebugEnabled()) {
                    if(nameSpace != null) {
                        LOG.debug("{}\t{},handle `{}`,result({}):{}",
                            req.uri, req.traceId, processName, nameSpace, hr.toString(23));
                    } else {
                        LOG.debug("{}\t{},handle `{}`,result:{}",
                            req.uri, req.traceId, processName, hr.toString(23));
                    }
                }
                saveData(nameSpace, hr.data, respData);
            }
            
            if(this.onSuccess != null) {
                HandleResult onSuccessHr = this.onSuccess.run(req, respData);
                if(onSuccessHr.code != RetCode.OK) {
                    return onSuccessHr;
                }
                saveData(nameSpace, onSuccessHr.data, respData);
            }
            return hr;
        }, Pool);
    }
    
    private static void saveData(String ns, Map<String, Object> data, Map<String, Object> dst) {
        if(data == dst) { //有的处理中，直接在respData中存放返回数据，此时无需转储
            return;
        }
        if(ns == null) {
            dst.putAll(data);
            return;
        }
        Map<String, Object> nsDta = ValParser.getAsObject(dst, ns);
        if(nsDta == null) {
            dst.put(ns, data); //用于避免名称空间冲突
        } else {
            nsDta.putAll(data);
        }
    }
    
    /**
     * 将脚本中的占位符替换成实际的值，这些值可以是请求参数，或者运行中返回的值。
     * @param elements 解析后的脚本
     * @param req 请求
     * @param resp 响应
     * @return 替换占位符后的脚本
     */
    public static String translateElements(ScriptElement[] elements,
        AbsServerRequest req, Map<String, Object> resp) {
        if(elements == null || elements.length == 0) {
            return null;
        }
        StringBuilder script = new StringBuilder(4096);
        for (ScriptElement se : elements) {
            script.append(se.run(req, resp));
        }
        return script.toString();
    }
    
    //注册支持的process类型，在VirtualServer中调用
    public static boolean register(String type, Class<? extends AbsProcessor> handler) {
        String t = type.toLowerCase();
        if(Processors.containsKey(t)) {
            return false;
        }
        Processors.put(t, handler);
        return true;
    }
    
    public static Class<? extends AbsProcessor> getProcessorCls(String name) {
        return Processors.get(name);
    }

    /**
     * 清除缓存，销毁时需要调用
     */
    public static void clearCache() {
        CachedResults.clear();
    }
    
    public static CompletableFuture<HandleResult> futureResult(int code, String info) {
        return CompletableFuture.completedFuture(new HandleResult(code, info));
    }
    
    public static CompletableFuture<HandleResult> futureResult(int code) {
        return CompletableFuture.completedFuture(new HandleResult(code));
    }
    
    public static CompletableFuture<HandleResult> futureResult(HandleResult result) {
        return CompletableFuture.completedFuture(result);
    }
    
    public static CompletableFuture<HandleResult> futureResult(Map<String, Object> data) {
        return CompletableFuture.completedFuture(new HandleResult(data));
    }
    
    public static CompletableFuture<HandleResult> futureResult() {
        return CompletableFuture.completedFuture(HandleResult.OK);
    }
    
    static class OnSuccess {
        private static final String CFG_CONDITION = "condition";
        private static final int TYPE_JS = 0; //java scripts
        private static final int TYPE_RS = 1; //runtime scripts
        private static final int TYPE_JSON = 2;

        final HandleResult errResult;
        final ScriptElement[] eles;
        final int type;
        
        OnSuccess(int code, String info, ScriptElement[] eles, int type) {
            this.eles = eles;
            this.errResult = new HandleResult(code, info);
            this.type = type;
        }
        
        static OnSuccess parse(Object o, Set<String> availableParas) {
            ScriptElement[] eles;
            int errorCode = -1;
            String errorInfo = null;
            int type = -1;
            
            if(o instanceof String) {
                String s = ((String)o).trim();
                type = TYPE_JS;
                //携带了js头，一定是js，其他不是json/rs的则认为是js
                if(s.startsWith(JS_HEAD)) {
                    s = s.substring(JS_HEAD.length());
                } else if(s.endsWith("}")) {
                    if(s.startsWith("{")) {
                        type = TYPE_JSON;
                    } else if(s.startsWith(ScriptElement.PLACEHOLDER_START)) {
                        type = TYPE_RS;
                    }
                }
                eles = ScriptElement.parsePlaceHolder(s, availableParas, "'", "'");
            } else { //js执行太慢，可以使用逻辑表达式代替
                Map<String, Object> cfg = ValParser.parseObject(o);
                String s = ValParser.getAsStr(cfg, CFG_CONDITION);
                if(StringUtil.isEmpty(s)) {
                    LOG.error("There is no {} cfg in onSuccess", CFG_CONDITION);
                    return null;
                }
                String condition = s.replace("&&", "*").replace("||", "+");
                eles = ScriptElement.parsePlaceHolder(condition, availableParas, "'", "'");
                errorCode = RetCode.parseCode(cfg.get(CFG_ERROR_CODE));
                errorInfo = ValParser.getAsStr(cfg, CFG_ERROR_INFO, RetCode.getInfo(errorCode));
            }
            if(eles == null || eles.length == 0) {
                return null;
            }
            return new OnSuccess(errorCode, errorInfo, eles, type);
        }
        
        /**
         * handle执行完之后执行的逻辑，可以用一段js实现
         * onSuccess中的引用resp的字段没有nameSpace
         * @param req 请求
         * @param respData 响应
         * @return 结果
         */
        HandleResult run(AbsServerRequest req, Map<String, Object> respData) {
            String script = translateElements(eles, req, respData);
            if(script == null) {
                LOG.error("Invalid script, fail to compile it with req");
                return new HandleResult(RetCode.INTERNAL_ERROR, "fail to compile js with req");
            }
            if(errResult.code < 0) { //未设置错误码，则认为是js脚本或是json对象
                String s = script.trim();
                if(type == TYPE_JS) {
                    return JsEngine.getHandleResult(s);
                }
                return HandleResult.tryParse(s);
            }
            
            double r = Calculator.calculate(script); //condition
            if(r > 0) {
                return HandleResult.OK;
            }
            return errResult;
        }
    }
}