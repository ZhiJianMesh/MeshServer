package cn.net.zhijian.mesh.frm.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IProcessor;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 接口处理信息
 * 接口的处理中可以包括多个IProcessor的子类，
 * 如果classpath中存在，则从本地加载，否则从服务目录的service.jar中加载
 * @author flyinmind of csdn.net
 */
public final class ProcessInfo {
    private static final String PROPERTY_MACRO = "macro";
    private static final String PROPERTY_NAME = "name";
    private static final String PROPERTY_TYPE = "type";
    private static final String PROPERTY_HANDLER = "handler";
    
    private static final Logger LOG = LogUtil.getInstance();
    private final IProcessor[] handlers;

    private ProcessInfo(int handleNum) {
        this.handlers = new IProcessor[handleNum];
    }

    public ProcessInfo(IProcessor[] handlers) {
        this.handlers = handlers;
    }

    public ProcessInfo(IProcessor p) {
        this.handlers = new IProcessor[]{p};
    }

    /**
     *
     * @param serviceInfo 服务信息
     * @param apiInfo api信息
     * @param url api的url
     * @param cfg api完整配置，包括参数等
     * @param macros 宏定义，从macros.def中解析出来
     * @param request 请求信息
     * @return 处理信息
     */
    @SuppressWarnings("unchecked")
    public static ProcessInfo parse(ServiceInfo serviceInfo, ApiInfo apiInfo, UrlPathInfo url, 
            Map<String, Object> cfg, String procName, Map<String, Object> macros, RequestInfo request) {
        Object o = cfg.get(procName);
        if(o == null) {
            LOG.error("There is no {} config in {}", procName, url);
            return null; //no process config, fail
        }
        
        List<Object> procList;
        if(o instanceof Map<?, ?>) {
            procList = new ArrayList<>();
            procList.add(o);
        } else if(o instanceof List<?>) {
            procList = ValParser.parseList(o);
        } else {
            LOG.error("Invalid {} config in {}", procName, url);
            return null;
        }

        int idx = 0;
        ProcessInfo pi = new ProcessInfo(procList.size());
        
        for(Object procCfg : procList) {
            Map<String, Object> one = ValParser.parseObject(procCfg);
            if(one == null || one.isEmpty()) {
                LOG.error("Invalid process config at {}, need a map, in {}", idx, url.toString());
                return null;
            }

            String macro = ValParser.getAsStr(one, PROPERTY_MACRO);
            if(!StringUtil.isEmpty(macro)) { //process宏定义
                Map<String, Object> mMacro = ValParser.parseObject(macros.get(macro));
                if(mMacro == null || (one = parseMacro(one, mMacro)) == null) {
                    LOG.error("Invalid process macro {} at {},in {}", macro, idx, url.toString());
                    return null;
                }
            }
            
            String name = ValParser.getAsStr(one, PROPERTY_NAME); //process name
            if(StringUtil.isEmpty(name)) {
                LOG.error("Invalid handler config, name is empty, at {} in {}", 
                        idx, url.toString());
                return null;
            }
            
            String type = ValParser.getAsStr(one, PROPERTY_TYPE).toLowerCase();
            if(StringUtil.isEmpty(type)) {
                LOG.error("Empty handler type at {}, {}, name {}, in {}", 
                        idx, one, name, url.toString());
                return null;
            }

            Class<? extends AbsProcessor> cls;
            String handler = ValParser.getAsStr(one, PROPERTY_HANDLER).trim();
            if(!StringUtil.isEmpty(handler)) {
                try {
                    cls = (Class<? extends AbsProcessor>)serviceInfo.classLoader.loadClass(handler);
                } catch (ClassNotFoundException e) {
                    LOG.error("Fail to load class {} in {}", handler, url.toString(), e);
                    return null;
                }

                Class<? extends AbsProcessor> superCls = AbsProcessor.getProcessorCls(type);
                if(!superCls.isAssignableFrom(cls)) {
                    LOG.error("Handler {} doesn't extend {}", handler, superCls.getName());
                    return null;
                }
            } else if(type.equals(IProcessor.TYPE_JAVA)) {
                LOG.error("There must be {} item, and it should extend AbsProcessor or its sub-classes", PROPERTY_HANDLER);
                return null;
            } else {
                cls = AbsProcessor.getProcessorCls(type);
            }
            
            if(cls == null) {
                LOG.error("Wrong handler type '{}' in handler {} at {} in {}",
                        type, name, idx, url.toString());
                return null;
            }

            try {
                AbsProcessor ap = cls.getConstructor(ServiceInfo.class, ApiInfo.class, String.class)
                        .newInstance(serviceInfo, apiInfo, name);
                if (!ap.parse(url, one, request)) {
                    LOG.error("Fail to call {}.parse of type={} in {},handler:{}",
                            name, type, url.toString(), cls.getName());
                    return null;
                }
                pi.handlers[idx] = ap;
            } catch (Exception e) {
                LOG.error("Fail to create handler {}, type={} in {},handler:{}",
                        name, type, url.toString(), cls.getName(), e);
                return null;
            }
            idx++;
        }
        return pi;
    }

    /**
     * 解析宏定义，所有的宏process必须都放在macros.def文件中
     * @param cfg 宏定义配置，可能携带了参数
     * @param macro 宏定义，从macros.def中取得
     * @return 宏替换后的process定义
     */
    private static Map<String, Object> parseMacro(Map<String, Object> cfg, Map<String, Object> macro) {
        /*
         * 如果只有一个字段，即只有macro:"xxx"，直接返回；
         * 如果大于1，则可能定义了宏参数，需要逐个解析。
         */
        if(cfg.size() == 1) {
            return macro;
        }
        String s = JsonUtil.objToJson(macro);
        Map<String, Object> otherCfgs = new HashMap<>(); //非宏参数
        for(Map.Entry<String, Object> e : cfg.entrySet()) {
            String p = e.getKey();
            if(p.equals(PROPERTY_MACRO)) {
                continue;
            }
            
            if(p.startsWith("#") && p.endsWith("#")) { //替换宏定义中的宏参数
                s = s.replace(p, ValParser.parseString(e.getValue()));                        
            } else {
                otherCfgs.put(p, e.getValue());
            }
        }
        Map<String, Object> macroCfgs = JsonUtil.jsonToMap(s);
        if(macroCfgs == null) {
            LOG.error("Fail to parse expanded macro `{}`", s);
            return null;
        }
        macroCfgs.putAll(otherCfgs); //将非宏参数合并到整个宏定义中
        return macroCfgs;
    }
    
    public void destroy() {
        for(IProcessor h : handlers) {
            h.destroy();
        }
    }
    
    /**
     * 处理请求，每个processor顺序执行
     * @param req 请求
     * @param respData 响应
     * @param Pool 线程池
     */
    public CompletableFuture<HandleResult> handle(AbsServerRequest req,
            Map<String, Object> respData, ExecutorService Pool) {
        CompletableFuture<HandleResult> f = handlers[0].handleAll(req, respData);

        /*
         * 逐个执行所有处理
         * 即使中间某个process处理失败了，后面的处理仍然会执行，
         * 但是在判断上一步返回码时，直接返回了。
         */
        for(int i = 1; i < handlers.length; i++) {
            int cur = i;
            f = f.thenComposeAsync(hr -> {
                if(hr.code != RetCode.OK) {
                    //无法终止执行链，后面所有的processor都会在此返回相同的hr
                    return CompletableFuture.completedFuture(hr);
                }
                return handlers[cur].handleAll(req, respData);
            }, Pool);
        }

        return f;
    }
}