package cn.net.zhijian.mesh.frm.method;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.abs.AbsTokenChecker;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ProcessInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ResponseInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.aclchecker.AclChecker;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.mesh.frm.config.tokenchecker.TokenCheckers;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IMethod;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IProcessor;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.frm.process.StaticProcessor;
import cn.net.zhijian.mesh.js.JsEngine;
import cn.net.zhijian.util.Calculator;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * API接口处理类
 * @author flyinmind of csdn.net
 *
 */
public class ApiMethod implements IMethod, IThreadPool {
    private static final Logger LOG = LogUtil.getInstance();
    public static final String CFG_NAME = "name";
    public static final String CFG_SAMEAS = "sameAs"; //与某个method一致
    private static final String CFG_METHOD = "method";
    private static final String CFG_FEATURE = "feature";
    //属性，比如public/private、localtion等
    private static final String CFG_PROPERTY = "property";
    private static final String CFG_TOKENCHECKER = "tokenChecker";
    private static final String CFG_ACLCHECKER = "aclChecker";
    //默认返回内容，当程序内部发生任何异常时，返回onException指定的结果
    private static final String CFG_ONEXCEPTION = "onException";
    private static final String CFG_WHILE = "while"; 
    private static final String CFG_PROCESS = "process";
    //基于属性的权限处理，aclChecker是ABAC、RoAAC或RaAAC时需要设置
    private static final String CFG_ACLPROCESS = "aclProcess";
    private static final String CFG_TAILPROCESS = "tailProcess"; //收尾的处理
    private static final String CFG_HEADPROCESS = "headProcess"; //循环前的处理
    private static final String CFG_TAIL_RUNTYPE = "tailRuntype"; 
    
    private static final String PROPERTY_PUBLIC = "PUBLIC";
    private static final String PROPERTY_PRIVATE = "PRIVATE";
    
    private static final int TAILRUN_ALL = 0;
    private static final int TAILRUN_FAIL = 1;
    private static final int TAILRUN_SUCCESS = 2;
    private static final int TAILRESULT_RETURN = 0;
    private static final int TAILRESULT_IGNORE = (1<<4);
    
    /**
     * API的基本信息，比如url、method等
     */
    public final ApiInfo apiInfo;

    /**
     * 服务的基本信息，此内容是从service.cfg中获得
     */
    public final ServiceInfo serviceInfo;
    
    /**
     * 如果接口是PRIVATE，则需要指定tokenChecker，默认为OAUTH2，
     * 请求时，需要携带access_token的头部
     */
    public final AbsTokenChecker tokenChecker;
    
    /**
     * RBAC用户级ACL鉴权，需要服务名称、群组ID、用户ID三个参数，
     * 先获取用户在服务的某个群组中的角色，然后判断角色是否可满足cls、feature的限制。
     * 可以用在tokenChecker为USER与OAUTH的接口中，
     * 如果是OAUTH，则需要额外传递调用此接口用户的gid、uid
     */
    public final AclChecker aclChecker;
    
    /**
     * 请求信息，主要是请求参数解析
     */
    public final RequestInfo request;

    /**
     * 处理信息，可以包活多个处理器，
     * 执行时，前面处理器执行完毕才会执行后面一个，
     * 所有的处理都是异步方式，不会堵塞HttpServer的netty线程
     */
    public final ProcessInfo process;
    
    //process之前的处理，普通method通常没有，因为process可以支持多个处理，在loop中通常有
    public final ProcessInfo headProcess;
    //process之后的处理，通常用于处理错误，在其中可以读取process中的#code、#info
    //无论headProcess、process成功或失败，都会执行，通常没有
    public final ProcessInfo tailProcess;

    /**
     * 响应信息，包括响应的参数，
     * 如果不配置，则每一个处理器执行的结果都会返回，
     * 如果配置了，则只返回响应参数中指定的字段，并且返回前会判断是否复合要求
     */
    public final ResponseInfo response;
    
    public final HandleResult onException;
    
    //判断tailProcess是否运行，如果运行是否返回结果
    //结果处理方法(4)|运行时机(4)
    protected int tailRuntype = TAILRESULT_RETURN + TAILRUN_ALL;

    /**
     * 
     * @param serviceInfo 服务信息
     * @param apiInfo api基本信息
     * @param request 请求配置
     * @param response 响应配置
     * @param process 处理配置
     * @param headProcess process之前的处理，通常没有
     * @param tailProcess process之后的处理，用于处理错误，无论process成功火失败，都会执行，通常没有
     * @param tokenChecker token检查
     * @param aclChecker 接入权限检查，只支持用户级的RBAC
     * @param onException 发生异常时的响应内容
     */
    public ApiMethod(ServiceInfo serviceInfo, ApiInfo apiInfo,
                     RequestInfo request, ResponseInfo response,
                     ProcessInfo process, ProcessInfo headProcess, ProcessInfo tailProcess,
                     AbsTokenChecker tokenChecker, AclChecker aclChecker,
                     HandleResult onException) {
        this.apiInfo = apiInfo;
        this.request = request;
        this.process = process;
        this.headProcess = headProcess;
        this.tailProcess = tailProcess;
        this.response = response;
        this.serviceInfo = serviceInfo;
        this.tokenChecker = tokenChecker;
        this.aclChecker = aclChecker;
        this.onException = onException == null ? HandleResult.InternalError : onException;

        if(!apiInfo.isFlagSet(ApiInfo.FLAG_PRIVATE)) {
            if(tokenChecker != null) {
                throw new IllegalArgumentException("token checker set in a public api:" + apiInfo.url);
            }
        } else if(tokenChecker == null) {
            throw new IllegalArgumentException("token checker not set in a private api:" + apiInfo.url);
        }
    }

    public ApiMethod(ServiceInfo serviceInfo, ApiInfo apiInfo,
            RequestInfo request, ResponseInfo response, ProcessInfo process,
            AbsTokenChecker tokenChecker, AclChecker aclChecker) {
        this(serviceInfo, apiInfo, request, response, process, null, null,
             tokenChecker, aclChecker, null);
    }
    
    private ApiMethod(ApiInfo apiInfo, ApiMethod sameAs,
            AbsTokenChecker tokenChecker, AclChecker aclChecker) {
        this(sameAs.serviceInfo, apiInfo,
             sameAs.request, sameAs.response,
             sameAs.process, sameAs.headProcess, sameAs.tailProcess,
             tokenChecker, aclChecker, sameAs.onException);
        this.tailRuntype = sameAs.tailRuntype;
    }
    /**
     * 解析json接口，这类接口只能是public的，且只能用get方法
     * @param serviceInfo 服务信息
     * @param name api名称
     * @param data 固定的响应内容
     * @return API对象
     */
    public static ApiMethod createStaticMethod(ServiceInfo serviceInfo, String name, Map<String, Object> data) {
        UrlPathInfo url = new UrlPathInfo(serviceInfo.name).push(IConst.SERVICE_URL_API).push(name);
        ApiInfo apiInfo = new ApiInfo(IConst.SERVICE_URL_ROOT, IConst.METHOD_GET, url, null, false);
        return new ApiMethod(serviceInfo, apiInfo,
                new RequestInfo(), new ResponseInfo(null),
                new ProcessInfo(new StaticProcessor(serviceInfo, apiInfo, name, data)),
                null, null);
    }

    /**
     * @param serviceInfo 服务信息
     * @param path 配置文件路径，比如'/serviceName/api/...'，如果是root，则为'/serviceName/api'
     * @param cls 分类，就是配置文件的名称
     * @param cfg 一个接口的配置
     * @param macros 宏定义，从macros.def中解析获得
     * @return API对象
     */
    public static ApiMethod parse(ServiceInfo serviceInfo,
            String path, String cls, Map<String, Object> cfg,
            Map<String, Object> macros) {
        String name = ValParser.getAsStr(cfg, CFG_NAME, null);
        if(StringUtil.isEmpty(name)) {
            LOG.error("Fail to get name from a method config of {}/{}", serviceInfo.name, path);
            return null;
        }
        
        UrlPathInfo url = new UrlPathInfo(path).push(name);
        String method = null;
        if(cfg.containsKey(CFG_METHOD)) {
            method = ValParser.getAsStr(cfg, CFG_METHOD);
        }

        String feature = ValParser.getAsStr(cfg, CFG_FEATURE, null);
        String s = ValParser.getAsStr(cfg, CFG_PROPERTY, PROPERTY_PUBLIC).toUpperCase();
        boolean isPrivate = s.contains(PROPERTY_PRIVATE);
        ApiInfo apiInfo = new ApiInfo(cls, method, url, feature, isPrivate);
        
        //必须先解析tokenChecker
        //因为tokenChecker中添加了系统参数，在request.VARS中可能会引用
        AbsTokenChecker tokenChecker = null;
        if(apiInfo.isFlagSet(ApiInfo.FLAG_PRIVATE)) {
            String tChk = ValParser.getAsStr(cfg, CFG_TOKENCHECKER).trim();
            /*
             * 默认的私有接口都用oAuth，调用时，调用方先向oAuth服务获取调用token，
             * 然后携带token向被调方发起请求，被调方会向oAuth服务确认token有效性，
             * 获得确认后才可以执行请求，否则响应NO_RIGHT。
             * 请求token时的secret是通过应用的私有签名“调用方名称+'_'+被调方名称”生成的。
             */
            if(StringUtil.isEmpty(tChk)) {
                LOG.error("{} not set in {}", CFG_TOKENCHECKER, url);
                return null;
            }
            try {
                tokenChecker = TokenCheckers.getChecker(tChk);
            } catch (Exception e) {
                LOG.error("Fail to create {}:{} in {}", CFG_TOKENCHECKER, tChk, url,  e);
            }
            if(tokenChecker == null) {
                LOG.error("Invalid {}:{} in {}", CFG_TOKENCHECKER, tChk, url);
                return null;
            }
            LOG.debug("/{}/api/{}/{},{}", serviceInfo.name, cls, name, tokenChecker.name);
            tokenChecker.addAvailables(apiInfo); //每种token增加了不同的token参数
        }
        
        RequestInfo request = RequestInfo.parse(serviceInfo.name, apiInfo, cfg);
        if(request == null) {
            LOG.error("Fail to load request config of method {}", url);
            return null;
        }

        /*
         * 响应解析时，如果类型为docx，saveAs配置需要用到请求参数，
         * 用于动态指定下载的文件名，只需请求参数即可，无需内置参数
         */
        ResponseInfo response = ResponseInfo.parse(serviceInfo, request.params(), cfg);
        if(response == null) {
            LOG.error("Fail to load response config of method {}", url);
            return null;
        }

        String sWhile = ValParser.getAsStr(cfg, CFG_WHILE).trim();
        //如果有while，可以加headProcess
        //因为headProcess中可能生成process需要的参数，所以要放在process之前
        ProcessInfo headProcess = null;
        if(cfg.containsKey(CFG_HEADPROCESS)) {
            headProcess = ProcessInfo.parse(serviceInfo, apiInfo, url,
                    cfg, CFG_HEADPROCESS, macros, request);
            if(headProcess == null) {
                LOG.error("Fail to load {} config of method {}", CFG_HEADPROCESS, url);
                return null;
            }
        }
        
        AclChecker aclChecker = null;
        //if a public api, need not tokenChecker or aclChecker
        if(apiInfo.isFlagSet(ApiInfo.FLAG_PRIVATE)) {
            //parse aclChecker
            String sAclChecker = ValParser.getAsStr(cfg, CFG_ACLCHECKER).trim();
            if(!StringUtil.isEmpty(sAclChecker)) {
                try {
                    ProcessInfo abacProcess = null;
                    if(cfg.containsKey(CFG_ACLPROCESS)) { //基于属性的权限检查
                        abacProcess = ProcessInfo.parse(serviceInfo, apiInfo, url,
                            cfg, CFG_ACLPROCESS, macros, request);
                    }
                    aclChecker = AclChecker.getChecker(sAclChecker, abacProcess);
                } catch (Exception e) {
                    LOG.error("Fail to create {}:{} in {}", CFG_ACLCHECKER, sAclChecker, url, e);
                    return null;
                }
    
                if(aclChecker == null || tokenChecker == null) {
                    LOG.error("Invalid aclChecker({}) or tokenChecker({}) in {}", aclChecker, tokenChecker, url);
                    return null;
                }
    
                if(!aclChecker.checkParameter(apiInfo, tokenChecker.isUserToken())) {
                    LOG.error("Fail to check parameters of {} {} in {}", CFG_ACLCHECKER, sAclChecker, url);
                    return null;
                }
            }
        }
        
        //process必须放在后面解析，因为request、token都会添加参数
        ProcessInfo process = ProcessInfo.parse(serviceInfo, apiInfo, url,
                cfg, CFG_PROCESS, macros, request);
        if(process == null) {
            LOG.error("Fail to load process config of `{}`", url);
            return null;
        }

        //默认返回内容，当发生任何异常时，返回default，没有，则直接返回错误
        HandleResult onException = null;
        Map<String, Object> def = ValParser.getAsObject(cfg, CFG_ONEXCEPTION);
        if(def != null) {
            if(!def.containsKey(HandleResult.CODE)) {
                LOG.error("Not a valid {} in `{}`", CFG_ONEXCEPTION, url);
                return null;
            }
            onException = HandleResult.fromMap(def);
        }
        
        ProcessInfo tailProcess = null;
        if(cfg.containsKey(CFG_TAILPROCESS)) {
            tailProcess = ProcessInfo.parse(serviceInfo, apiInfo, url,
                cfg, CFG_TAILPROCESS, macros, request);
            if(tailProcess == null) {
                LOG.error("Fail to load {} config of method {}", CFG_TAILPROCESS, url);
                return null;
            }
        }

        ApiMethod am;
        if(!StringUtil.isEmpty(sWhile)) {
            am = new LoopMethod(serviceInfo, apiInfo, request, response,
                         process, headProcess, tailProcess,
                         tokenChecker, aclChecker, onException, sWhile);
        } else {
            am = new ApiMethod(serviceInfo, apiInfo, request, response,
                         process, headProcess, tailProcess,
                         tokenChecker, aclChecker, onException);
        }
        
        if(tailProcess != null) {
            String tailRuntype = ValParser.getAsStr(cfg, CFG_TAIL_RUNTYPE);
            if(!StringUtil.isEmpty(tailRuntype)) {
                tailRuntype = tailRuntype.toUpperCase();
                if(tailRuntype.contains("ALL")) {
                    am.tailRuntype = (am.tailRuntype & 0xf0) | TAILRUN_ALL;
                } else if(tailRuntype.contains("FAIL")) {
                    am.tailRuntype = (am.tailRuntype & 0xf0) | TAILRUN_FAIL;
                } else if(tailRuntype.contains("SUCCESS")) {
                    am.tailRuntype = (am.tailRuntype & 0xf0) | TAILRUN_SUCCESS;
                }

                if(tailRuntype.contains("RETURN")) {
                    am.tailRuntype = (am.tailRuntype & 0x0f) | TAILRESULT_RETURN;
                } else if(tailRuntype.contains("IGNORE")) {
                    am.tailRuntype = (am.tailRuntype & 0x0f) | TAILRESULT_IGNORE;
                }
            }
        }
        
        return am;
    }
    
    /**
     * 解析带有sameAs的接口定义
     * @param name 名称
     * @param path 配置文件路径，比如'/serviceName/api/...'，如果是root，则为'/serviceName/api'
     * @param cls 分类，就是配置文件的名称
     * @param cfg 配置
     * @param sameAs 相同的method
     * @return 接口定义
     */
    public static ApiMethod parse(String name, String path, String cls,
            Map<String, Object> cfg, ApiMethod sameAs, Map<String, Object> macros) {
        ServiceInfo serviceInfo = sameAs.serviceInfo;
        UrlPathInfo url = new UrlPathInfo(path).push(name);
        String method = null;
        if(cfg.containsKey(CFG_METHOD)) {
            method = ValParser.getAsStr(cfg, CFG_METHOD);
        }

        String feature = ValParser.getAsStr(cfg, CFG_FEATURE, null);
        String s = ValParser.getAsStr(cfg, CFG_PROPERTY, PROPERTY_PUBLIC).toUpperCase();
        boolean isPrivate = s.contains(PROPERTY_PRIVATE);
        ApiInfo apiInfo = new ApiInfo(cls, method, url, feature, isPrivate);
        
        //必须先解析tokenChecker
        //因为tokenChecker中添加了系统参数，在request.VARS中可能会引用
        AbsTokenChecker tokenChecker = null;
        if(apiInfo.isFlagSet(ApiInfo.FLAG_PRIVATE)) {
            String tChk = ValParser.getAsStr(cfg, CFG_TOKENCHECKER).trim();
            /*
             * 默认的私有接口都用oAuth，调用时，调用方先向oAuth服务获取调用token，
             * 然后携带token向被调方发起请求，被调方会向oAuth服务确认token有效性，
             * 获得确认后才可以执行请求，否则响应NO_RIGHT。
             * 请求token时的secret是通过应用的私有签名“调用方名称+'_'+被调方名称”生成的。
             */
            if(StringUtil.isEmpty(tChk)) {
                LOG.error("{} not set in {}", CFG_TOKENCHECKER, url);
                return null;
            }
            try {
                tokenChecker = TokenCheckers.getChecker(tChk);
            } catch (Exception e) {
                LOG.error("Fail to create {}:{} in {}", CFG_TOKENCHECKER, tChk, url, e);
            }
            if(tokenChecker == null) {
                LOG.error("Invalid {}:{} in {}", CFG_TOKENCHECKER, tChk, url);
                return null;
            }
            LOG.debug("/{}/api/{}/{},{}", serviceInfo.name, cls, name, tokenChecker.name);
            tokenChecker.addAvailables(apiInfo); //每种token增加了不同的token参数
        }
        
        //if a public api, need not tokenChecker or aclChecker
        if(!apiInfo.isFlagSet(ApiInfo.FLAG_PRIVATE)) {
            if(sameAs.apiInfo.isFlagSet(ApiInfo.FLAG_PRIVATE)) {
                LOG.error("Can't import a private api in a public api in {}", url);
                return null;
            }
            return new ApiMethod(apiInfo, sameAs, null, null);
        }

        //parse aclChecker
        AclChecker aclChecker = null;
        String sAclChecker = ValParser.getAsStr(cfg, CFG_ACLCHECKER).trim();
        if(!StringUtil.isEmpty(sAclChecker)) {
            if(tokenChecker == null) {
                LOG.error("Can't set aclChecker when no tokenChecker in {}", url);
                return null; //如果设置了aclChecker就必须设置tokenChecker
            }
            
            try {
                ProcessInfo abacProcess = null;
                if(cfg.containsKey(CFG_ACLPROCESS)) {
                    abacProcess = ProcessInfo.parse(serviceInfo, apiInfo, url,
                        cfg, CFG_ACLPROCESS, macros, sameAs.request);
                }
                aclChecker = AclChecker.getChecker(sAclChecker, abacProcess);
            } catch (Exception e) {
                LOG.error("Fail to create {}:{} in {}", CFG_ACLCHECKER, sAclChecker, url, e);
                return null;
            }

            if(aclChecker == null) {
                LOG.error("Invalid aclChecker in {}", url);
                return null; //如果设置了aclChecker就必须设置tokenChecker
            }

            if(!aclChecker.checkParameter(apiInfo, tokenChecker.isUserToken())) {
                LOG.error("Fail to check parameters of {} {} in {}", CFG_ACLCHECKER, sAclChecker, url);
                return null;
            }
        }
        
        return new ApiMethod(apiInfo, sameAs, tokenChecker, aclChecker);
    }
    
    /**
     * 处理请求，每个processor顺序执行
     * @param req 请求
     * @param resp 响应
     */
    protected void handle(AbsServerRequest req, AbsServerResponse resp, Map<String, Object> respData) {
        CompletableFuture<HandleResult> cf;
        if(headProcess != null) {
            cf = headProcess.handle(req, respData, Pool).thenComposeAsync(hr -> {
                if(hr.code != RetCode.OK) {
                    return CompletableFuture.completedFuture(hr);
                }
                return process.handle(req, respData, Pool);
            });
        } else {
            cf = process.handle(req, respData, Pool);
        }
        cf.whenCompleteAsync((h, e) -> { //最后对响应内容进行处理
            if(e != null) {
                LOG.error("{}\t{},fail to response", apiInfo.url, req.traceId, e);
                resp.end(HandleResult.InternalError);
                req.getStat().incExceptions(1);
                return;
            }

            if(tailProcess != null) {
                int runType = tailRuntype & 0xf;
                if(runType == TAILRUN_ALL
                   || (h.code != RetCode.OK && runType == TAILRUN_FAIL)
                   || (h.code == RetCode.OK && runType == TAILRUN_SUCCESS)) {
                    int result = tailRuntype & 0xf0;
                    LOG.debug("execute {}.{},tailRuntype:{}", req.uri, CFG_TAILPROCESS, tailRuntype);
                    if(result == TAILRESULT_RETURN) {
                        tailProcess.handle(req, respData, Pool).whenCompleteAsync((hr, ex) -> handleFinish(req, resp, respData, hr), Pool);
                        return;
                    }
                    tailProcess.handle(req, respData, Pool);//不需要返回
                }
            }
            handleFinish(req, resp, respData, h);
        }, Pool);
    }
    
    protected void handleFinish(AbsServerRequest req, AbsServerResponse resp, Map<String, Object> respData, HandleResult hr) {
        if(hr.code != RetCode.OK){
            resp.end(hr);
            req.getStat().incFailures(1);
            return;
        }
        /*
         * 从resp中，将需要的数据按response的定义，汇聚起来，返回给调用方，
         * 在最后一个任务中对resp进行转义，可以过滤掉不必要的响应数据
         **/
        response.response(req, resp, respData);
    }
    
    public void execute(AbsServerRequest req, AbsServerResponse resp) {
        HandleResult hr = request.check(req);
        if(hr.code != RetCode.OK) {
            if(LOG.isDebugEnabled()){
                LOG.debug("{}\t{},executed parameters-check, result:{}",
                        req.uri, req.traceId, hr.toString(23));
            }
            resp.end(hr);//参数不正确，不必继续后面的处理
            return;
        }
        /*
         * respData用于存放每一个handler处理的中间数据，
         * 每一个handler中，执行完成后，数据不必存入，因为在AbsProcessor.handleAll中会处理。
         * 下一步处理如果依赖前面步骤的结果，可以使用@[xxx]引用。
         * 如果在最终返回中，无需各步的执行结果，可以配置reponse，将多余的部分过滤掉
         * 必须放在initVars之前，否则如果vars中引用它们就会失败
         */
        Map<String, Object> respData = new HashMap<>();
        if (!apiInfo.isFlagSet(ApiInfo.FLAG_PRIVATE)) {
            request.initVars(req, respData);
            handle(req, resp, respData); //公开的接口，直接执行
            return;
        }

        String token = req.header(IOAuth.HEAD_ACCESS_TOKEN);
        if(StringUtil.isEmpty(token)) {
            resp.end(new HandleResult(RetCode.NO_RIGHT, respData));
            if(LOG.isDebugEnabled()) {
                LOG.debug("No token head in `{}`", apiInfo);
            }
            req.getStat().incFailures(1);
            return;
        }

        checkToken(req, token).thenComposeAsync(at -> {
            if(at == null) {
                LOG.debug("Invalid token in {},caller:{},cid:{}",
                        req.uri, AccessToken.getCaller(token), req.cid());
                return HandleResult.future(RetCode.INVALID_TOKEN);
            }

            if(at.tokenType() == AccessToken.TOKENTYPE_SERVICE
              && !canCallFeature(apiInfo.feature, at.ext)) {
                LOG.debug("No right in {}, caller:{}", req.uri, at.caller);
                return HandleResult.future(RetCode.NO_RIGHT);
            }

            req.setToken(at); //token存入req中，作为req的一部分，后面的处理中会用到
            tokenChecker.addParas(req.params(), at); //扩展token参数，便于脚本中引用token参数
            request.initVars(req, respData);//有token时，vars中可能会使用token参数，所以放在token之后
            if(LOG.isDebugEnabled()) {
                LOG.debug("Token-check ok in {}, caller:{}, callee:{}({}), cid:{}, token:{}",
                        req.uri, at.caller, at.callee, IOAuth.TOKENTYPE_NAMES[at.tokenType()], 
                        req.cid(), at);
            }
            if(aclChecker == null) {
                return HandleResult.future();
            }
            //aclChecker中的响应不必往下传递，所以新建一个map
            return aclChecker.check(apiInfo, req, new HashMap<>());
        }, Pool).whenCompleteAsync((aclChkResult, e) -> {
            if(e != null) {
                resp.end(HandleResult.InternalError);
                req.getStat().incExceptions(1);
                LOG.error("Token check failed in {}, caller:{}", req.uri, AccessToken.getCaller(token), e);
                return;
            }

            if(aclChkResult.code != RetCode.OK) {
                resp.end(aclChkResult); //调用方需更新token或放弃
                req.getStat().incFailures(1);
                LOG.warn("Token check failed in {}, caller:{}", req.uri, AccessToken.getCaller(token));
                return;
            }
            handle(req, resp, respData);
        }, Pool);
    }

    @Override
    public boolean isValidMethod(String method) {
        return method.equals(this.apiInfo.method);
    }

    @Override
    public boolean isPrivate() {
        return this.apiInfo.isFlagSet(ApiInfo.FLAG_PRIVATE);
    }

    @Override
    public void destroy() {
        this.process.destroy();
        if(this.aclChecker != null) {
            this.aclChecker.destroy();
        }
        if(this.headProcess != null) {
            this.headProcess.destroy();
        }
        if(this.tailProcess != null) {
            this.tailProcess.destroy();
        }
    }

    private CompletableFuture<AccessToken> checkToken(AbsServerRequest req, String token) {
        return tokenChecker.check(req, token);
    }

    /**
     * 产生一个method
     * @param processor 处理类
     * @param apiInfo api信息
     * @param requestInfo 请求信息
     * @param tokenChecker token检查
     * @param aclChecker acl检查
     * @return api接口
     */
    public static ApiMethod generate(IProcessor processor, ApiInfo apiInfo,
                                     RequestInfo requestInfo, AbsTokenChecker tokenChecker, AclChecker aclChecker) {
        return new ApiMethod(processor.serviceInfo(), apiInfo,
                requestInfo, new ResponseInfo(null),
                new ProcessInfo(processor), null, null,
                tokenChecker, aclChecker, null);
    }
    
    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();
        map.put("url", apiInfo.url);
        map.put("cls", apiInfo.cls);
        if(!StringUtil.isEmpty(apiInfo.feature)) {
            map.put("feature", apiInfo.feature);
        }
        map.put("method", apiInfo.method);
        String property;

        if(apiInfo.isFlagSet(ApiInfo.FLAG_PRIVATE)) {
            property = PROPERTY_PRIVATE;
            if(aclChecker != null) {
                map.put("aclChecker", aclChecker.name);
            }
            if(tokenChecker != null) {
                map.put("tokenChecker", tokenChecker.name);
            }
        } else {
            property = PROPERTY_PUBLIC;
        }

        map.put("property", property);
        return map;
    }
    /**
     * 服务间token，被调方判断token是否允许调用此feature
     * @param apiFeature 被调方接口申明的特性名称，没有定义则传null
     * @param tokenFeature token中记录的feature
     * @return 是否可以调用，true为可以调用
     */
    private static boolean canCallFeature(String apiFeature, String tokenFeature) {
        if(apiFeature == null) {
            return true;
        }

        int el;
        if(tokenFeature == null || (el = tokenFeature.length()) == 0) {
            return false;
        }
        
        if(tokenFeature.indexOf('*') >= 0) {//只要存在*，则表示可以执行任何操作
            return true;
        }
        
        int pos = tokenFeature.indexOf(apiFeature);
        if(pos < 0) {
            return false;
        }

        if(pos > 0 && tokenFeature.charAt(pos - 1) != ',') {//...,feature...
            return false;
        }
        
        int fl = apiFeature.length();
        return !(pos + fl < el && tokenFeature.charAt(pos + fl) != ',');
    }
    
    static class LoopMethod extends ApiMethod {
        static final String LOOP_COUNT = "#loopCount";
        final ScriptElement[] loopCondition;
        final boolean jsCondition;

        LoopMethod(ServiceInfo serviceInfo, ApiInfo apiInfo,
                RequestInfo request, ResponseInfo response,
                ProcessInfo process, ProcessInfo headProcess, ProcessInfo tailProcess,
                AbsTokenChecker tokenChecker, AclChecker aclChecker, HandleResult onException,
                String loopCondition) {
            super(serviceInfo, apiInfo, request, response,
                  process, headProcess, tailProcess,
                  tokenChecker, aclChecker, onException);
            apiInfo.addExtPara(LOOP_COUNT); //可以作为循环变量
            Set<String> availableParas = apiInfo.availableSysParas();
            availableParas.addAll(request.params());
            this.jsCondition = loopCondition.startsWith(IConst.JS_HEAD)
               || loopCondition.contains("Mesh.success")
               || loopCondition.contains("Mesh.error");
            String condition = loopCondition;
            if(!this.jsCondition) {
                condition = loopCondition.replace("||", "+").replace("&&", "*");
            } else if(loopCondition.startsWith(IConst.JS_HEAD)) {
                condition = loopCondition.substring(IConst.JS_HEAD.length());
            }
            this.loopCondition = ScriptElement.parsePlaceHolder(condition, availableParas, null, null);
        }

        @Override
        protected void handle(AbsServerRequest req, AbsServerResponse resp, Map<String, Object> respData) {
            req.put(LOOP_COUNT, 0);
            CompletableFuture<HandleResult> cf;
            if(this.headProcess == null) {
                cf = loop(req, respData);
            } else {
                LOG.debug("execute {}.{}", req.uri, CFG_HEADPROCESS);
                cf = this.headProcess.handle(req, respData, Pool).thenComposeAsync(hr -> {
                    if(hr.code != RetCode.OK) { 
                        return CompletableFuture.completedFuture(hr);
                    }
                    return loop(req, respData);
                }, Pool);
            }
            cf.whenCompleteAsync((h, e) -> {
                if(e != null) {
                    LOG.error("{}\t{},fail to execute", apiInfo.url, req.traceId, e);
                    req.getStat().incExceptions(1);
                    return;
                }
                if(h.code != RetCode.OK) { 
                    req.getStat().incFailures(1);
                }

                if(this.tailProcess != null) {
                    int runType = tailRuntype & 0x0f;
                    if(runType == TAILRUN_ALL
                       || (h.code != RetCode.OK && runType == TAILRUN_FAIL)
                       || (h.code == RetCode.OK && runType == TAILRUN_SUCCESS)) {
                         LOG.debug("execute {}.{}", req.uri, CFG_TAILPROCESS);
                         tailProcess.handle(req, respData, Pool);//不需要返回
                    }
                }
            }, Pool);
            //不等执行完，就直接响应成功，等循环执行完后，在tailProcess中通常执行回调
            resp.end(HandleResult.OK);
        }
        
        private CompletableFuture<HandleResult> loop(AbsServerRequest req, Map<String, Object> respData) {
            return this.process.handle(req, respData, Pool).thenComposeAsync(hr -> {
                int count = req.getInt(LOOP_COUNT);
                LOG.debug("loop count:{}", count);
                count++; //每循环一次，count加1，可以作为结束条件
                req.put(LOOP_COUNT, count);
                //上一个循环的执行结果，AbsProcessor.handleAll只有失败时才会记录
                //此处无论成功失败都记录，在while的判断中可以使用它
                req.put(IConst.EMBEDED_CODE, hr.code);
                req.put(IConst.EMBEDED_INFO, hr.info);

                boolean continueLoop;
                String condition = AbsProcessor.translateElements(this.loopCondition, req, respData);
                if(this.jsCondition) {
                    HandleResult whileResult = JsEngine.getHandleResult(condition);
                    continueLoop = whileResult.code == RetCode.OK;
                    req.putAll(whileResult.data); //data放在request中，覆盖部分请求参数
                } else {
                    continueLoop = Calculator.calculate(condition) > 0.0;
                }
                if(continueLoop) {
                    return loop(req, respData);
                }
                return CompletableFuture.completedFuture(hr);//以最后一步的结果为最终结果
            }, Pool);
        }
    }
}