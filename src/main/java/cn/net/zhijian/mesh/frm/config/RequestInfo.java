package cn.net.zhijian.mesh.frm.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.para.ParameterInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.js.JsEngine;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 接口定义中请求部分的信息
 * @author flyinmind of csdn.net
 *
 */
public final class RequestInfo {
    private static final Logger LOG = LogUtil.getInstance();

    private static final String PROPERTY_REQUEST = "request";
    private static final String PROPERTY_VARS = "vars";
    
    private final ParameterInfo[] parameters;
    private final Var[] vars;

    private RequestInfo(ParameterInfo[] parameters, Var[] vars) {
        this.parameters = parameters;
        this.vars = vars;
    }
    
    public RequestInfo(ParameterInfo[] parameters) {
        this(parameters, null);
    }
    
    public RequestInfo() {
        this(null, null);
    }
    
    public static RequestInfo parse(String service, ApiInfo ai, Map<String, Object> cfg) {
        List<ParameterInfo> parameters = new ArrayList<>();
        List<String> dependencies = new ArrayList<>();
        List<Object> segs = ValParser.getAsList(cfg, PROPERTY_REQUEST);
        if(segs != null && !segs.isEmpty()) {
            int idx = 0;
            for(Object seg : segs) {
                Map<String, Object> one = ValParser.parseObject(seg);
                if(one == null || one.isEmpty()) {
                    LOG.error("Invalid request segments definition at {}.{}.{}", service, ai.url, idx);
                    return null;
                }
                ParameterInfo pi = ParameterInfo.parse(one, false);
                if (pi == null) {
                    LOG.error("Invalid request parameter definition at {}.{}.{}", service, ai.url, idx);
                    return null;
                }
                parameters.add(pi);
                idx++;
                dependencies.addAll(pi.dependParas());
            }            
        }
        
        Set<String> availableParas = ai.availableSysParas();
        for(ParameterInfo pi : parameters) {
            availableParas.addAll(pi.paras(""));
        }
        
        for(String pn : dependencies) {
            if(!availableParas.contains(pn)) {
                LOG.error("Invalid request parameter dependency of {}", pn);
                return null;
            }
        }
        
        List<Object> varList = ValParser.getAsList(cfg, PROPERTY_VARS);
        Var[] vars = null;
        if(varList != null && !varList.isEmpty()) {
            vars = parse(service, availableParas, varList);
        }
        
        if(parameters.isEmpty()) {
            return new RequestInfo(null, vars);
        }
        return new RequestInfo(parameters.toArray(new ParameterInfo[0]), vars);
    }

    /**
     * 检查请求参数是否都满足要求
     * @param req 请求信息
     * @return 检查结果
     */
    public HandleResult check(AbsServerRequest req) {
        if(parameters == null) {
            return HandleResult.OK;
        }

        String name;
        int idx = 1;
        Map<String, Object> params = req.params();
        for(ParameterInfo pi : parameters) {
            name = pi.name();
            Value v = pi.valueOf(req, params, params);
            if(!v.ok) {
                return new HandleResult(RetCode.WRONG_PARAMETER + idx, v.errInfo);
            }
            
            if(v.changed) { //变化了，才需要回填到请求参数中，比如做了类型转换、映射等操作
                req.put(name, v.v);
            }
            idx++;
        }
        return HandleResult.OK;
    }
    
    /**
     * 将定义的变量存入请求参数中
     * 脚本处理时，当做普通参数使用
     * @param req 请求信息
     * @param resp 响应参数
     */
    public void initVars(AbsServerRequest req, Map<String, Object> resp) {
        if(vars == null) {
            return;
        }
        for(Var v : vars) {
            v.run(req, resp);
        }
    }

    /**
     * 获得参数信息，都用于解析阶段，所以没有用Map
     * @param name 参数名称
     * @return 参数配置信息
     */
    public ParameterInfo getParameterInfo(String name) {
        if(parameters == null) {
            return null;
        }
        for(ParameterInfo pi : parameters) {
            if(name.equals(pi.name())) {
                return pi;
            }
        }
        return null;
    }
    
    /**
     * 获得所有参数及变量名称，用于解析阶段，所以没有保存到内存中
     * @return 参数、变量列表
     */
    public Set<String> params() {
        Set<String> params = new HashSet<>();
        if(parameters != null) {
            for(ParameterInfo pi : parameters) {
                params.addAll(pi.paras(IConst.EMPTY_STR));
            }
        }

        if(vars != null) {
            for(Var v : vars) {
                params.add(v.name);
            }
        }
        return params;
    }
    
    private static Var[] parse(String service, Set<String> availableParas, List<Object> varList) {
        Var[] vars = new Var[varList.size()];
        int idx = 0;

        for(Object l : varList) {
            Map<String, Object> one = ValParser.parseObject(l);
            if(one == null || one.isEmpty()) {
                LOG.error("Invalid request var definition at {}", idx);
                return null;
            }
            Var var = Var.parse(service, availableParas, one);
            if(var == null) {
                LOG.error("Fail to parse No.{} var {}", idx, one);
                return null;
            }
            availableParas.add(var.name);
            vars[idx++] = var;
        }
        return vars;
    }

    /**
     * 使用list，在json加载时可以保证顺序不变，
     * 使得上一个参数可以用在下一个参数的定义中
     * @param service 服务名
     * @param ai 接口信息
     * @param varList vars配置列表
     * @return vars数组
     */
    public Var[] parse(String service, ApiInfo ai, List<Object> varList) {
        if(varList == null || varList.isEmpty()) {
            return null;
        }
        Set<String> availableParas = ai.availableSysParas();
        availableParas.addAll(params());
        return parse(service, availableParas, varList);
    }

    public static final class Var {
        private static final String PROPERTY_TO_RESP = "toResp";
        private static final String PROPERTY_LIST = "list";
        private static final String PROPERTY_VAL = "val";
        
        public final String name;
        private final boolean toResp; //如果为true，则放入response的data中
        private final ScriptElement[] elements;
        private final ScriptElement[] listNum;
        private final boolean isJs; //js效率偏低，不建议在大并发的接口中使用
        
        Var(String name, ScriptElement[] elements, boolean response, ScriptElement[] list, boolean isJs) {
            this.name = name;
            this.elements = elements;
            this.toResp = response;
            this.listNum = list;
            this.isJs = isJs;
        }
        
        private static Var parse(String service, Set<String> availableParas, Map<String, Object> cfg) {
            String name = ValParser.getAsStr(cfg, ParameterInfo.PROPERTY_NAME);
            if (StringUtil.isEmpty(name)) {
                LOG.error("Invalid request vars definition in {}", cfg);
                return null;
            }
            String s = ValParser.getAsStr(cfg, PROPERTY_VAL);
            boolean isJs = false;
            if(s.startsWith(IConst.JS_HEAD)) {
                s = s.substring(IConst.JS_HEAD.length());
                isJs = true;
            }
            ScriptElement[] elements = ScriptElement.parsePlaceHolder(s, availableParas, IConst.EMPTY_STR, null);
            if (elements == null || elements.length == 0) {
                LOG.error("Invalid request val(`{}`) definition in var `{}`", s, cfg);
                return null;
            }

            boolean toResp = ValParser.getAsBool(cfg, PROPERTY_TO_RESP, false);
            String listCfg = ValParser.getAsStr(cfg, PROPERTY_LIST, "");
            ScriptElement[] list = null;
            if(!StringUtil.isEmpty(listCfg)) {
                list = ScriptElement.parsePlaceHolder(listCfg, availableParas, IConst.EMPTY_STR, null);
                if (list == null || list.length == 0) {
                    LOG.error("Invalid request listNum(`{}`) definition in var `{}`", listCfg, cfg);
                    return null;
                }
            }
            
            return new Var(name, elements, toResp, list, isJs);
        }
        
        public void run(AbsServerRequest req, Map<String, Object> resp) {
            Object var;
            if(listNum == null) {
                String s = compile(req, resp, elements);
                if(isJs) {
                    s = JsEngine.getString(s);
                }
                var = s;
            } else {
                int num = ValParser.parseInt(compile(req, resp, listNum), 0);
                List<String> ss = new ArrayList<>(num);
                //多次运行同一个逻辑，所以var定义中需要有sequence之类的占位符
                for(int i = 0; i < num; i++) {
                    String s = compile(req, resp, elements);
                    if(isJs) {
                        s = JsEngine.getString(s);
                    }
                    ss.add(s);
                }
                var = ss;
            }
            req.put(name, var);
            
            if(toResp) {
                resp.put(name, var);
            }
        }
        
        private static String compile(AbsServerRequest req, Map<String, Object> resp, ScriptElement[] elements) {
            StringBuilder str = new StringBuilder(4096);

            for (ScriptElement se : elements) {
                Object o = se.run(req, resp);
                if(o == null) {
                    LOG.error("Fail to get seg {} from req or resp", se.firstName());
                    continue;
                }

                if(o instanceof String || o instanceof Number || o instanceof Boolean) {
                    str.append(o);
                } else {
                    str.append(JsonUtil.objToJson(o));
                }
            }
            return str.toString();
        }
    }
}
