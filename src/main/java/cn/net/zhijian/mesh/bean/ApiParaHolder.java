package cn.net.zhijian.mesh.bean;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 接口配置中的函数参数占位符，比如@{ABSHASH|p1,p2.p3,'hello',3.14}，
 * '|'后面的每个参数，对应一个ApiParaHolder
 * @author flyinmind of csdn.net
 *
 */
public class ApiParaHolder implements IConst {
    public static final char PARA_SEPARATOR = ','; //参数分隔符
    public static final String STR_NULL = "null";
    public static final String STR_TRUE = "true";
    public static final String STR_FALSE = "false";
    public static final String STR_REQUEST = "request";
    public static final String STR_RESPONSE = "response";
    static final ApiParaHolder TRUE;
    static final ApiParaHolder FALSE;
    static final ApiParaHolder REQUEST;
    static final ApiParaHolder RESPONSE;
    static final ApiParaHolder NULL;

    static {
        REQUEST = new ApiParaHolder(STR_REQUEST, false, ParameterType.BUILTIN) {
            public Object get(AbsServerRequest req, Map<String, Object> respData) {
                return req.params();
            }

            public String getAsString(AbsServerRequest req, Map<String, Object> respData) {
                return JsonUtil.objToJson(req.params());
            }
        };

        RESPONSE = new ApiParaHolder(STR_RESPONSE, false, ParameterType.BUILTIN) {
            public Object get(AbsServerRequest req, Map<String, Object> respData) {
                return respData;
            }

            public String getAsString(AbsServerRequest req, Map<String, Object> respData) {
                return JsonUtil.objToJson(respData);
            }
        };

        NULL = new ConstParaHolder(STR_NULL) {
            public Object get(AbsServerRequest req, Map<String, Object> respData) {
                return null;
            }
        };

        TRUE = new ConstParaHolder(STR_TRUE) {
            public Object get(AbsServerRequest req, Map<String, Object> respData) {
                return true;
            }
        };

        FALSE = new ConstParaHolder(STR_FALSE) {
            public Object get(AbsServerRequest req, Map<String, Object> respData) {
                return false;
            }
        };
    }

    public enum ParameterType {
        REQ, //请求参数
        RESP, //以！开头，前面步骤返回的响应参数，
        HEAD, //以^开头，请求头中的字段
        BUILTIN //内置的，不属于请求或响应，比如全部请求参数、响应结果，或者true、false、null
    }
    
    public final String name;
    public final ParameterType paraType;
    /*多级，需要使用getObject，性能会弱一些，所以这里记录下来，尽量不在运行时判断*/
    private final boolean isMulti; 

    public ApiParaHolder(String name, boolean isMulti, ParameterType paraType) {
        this.name = name;
        this.isMulti = isMulti;
        this.paraType = paraType;
    }
    
    public Object get(AbsServerRequest req, Map<String, Object> respData) {
        switch (paraType) {
            case REQ: return getAsObj(req.params());
            case RESP: return getAsObj(respData);
            case HEAD: return req.header(name);
            default: return null; //impossible
        }
    }
    
    public String getAsString(AbsServerRequest req, Map<String, Object> respData) {
        switch (paraType) {
            case REQ: return getAsStr(req.params());
            case RESP: return getAsStr(respData);
            case HEAD: return req.header(name);
            default: return null; //impossible
        }
    }

    private String getAsStr(Map<String, Object> data) {
        if(isMulti) { //形如 a.b.c，需要多级获取
            Object o = ValParser.getObject(data, name);
            return o == null ? IConst.EMPTY_STR : o.toString();
        }
        return ValParser.getAsStr(data, name);
    }

    private Object getAsObj(Map<String, Object> data) {
        if(isMulti) { //形如 a.b.c，需要多级获取
            return ValParser.getObject(data, name);
        }
        return data.get(name);
    }

    public boolean isRequestPara() {
        return paraType == ParameterType.REQ;
    }
    
    public boolean isConst() { //不随请求内容变化的部分，比如字符串、null、bool
        return false;
    }
    
    public static ApiParaHolder parse(String s) {
        char ch = s.charAt(0);

        //单引号标识字符串开始，则name是一个字符串，不必从参数中解析；单引号是为了兼容老版本
        if (ch == QUOTATION_MARK || ch == '\'') {
            return new StrParaHolder(s.substring(1, s.length() - 1));
        }

        //数字当做普通字符串处理，但是在处理时，按需要，可转为数字；
        if (s.matches("-?\\d+(\\.\\d+)?")) {
            return new StrParaHolder(s);
        }

        String ls = s.toLowerCase();
        //bool型，只接受true或false
        if (STR_TRUE.equals(ls)) {
            return TRUE;
        }
        if (STR_FALSE.equals(ls)) {
            return FALSE;
        }
        //null不区分类型，独立出来
        if(STR_NULL.equals(ls)) {
            return NULL;
        }
        //全部请求参数
        if(STR_REQUEST.equals(ls)) {
            return REQUEST;
        }
        //全部响应结果
        if(STR_RESPONSE.equals(ls)) {
            return RESPONSE;
        }

        String name;
        ParameterType dataType;
        if (ch == BEGIN_CHAR_RESPONSE) {
            name = s.substring(1);
            dataType = ParameterType.RESP;
        } else if (ch == BEGIN_CHAR_HEADER) {
            name = s.substring(1);
            dataType = ParameterType.HEAD;
        } else {
            name = s;
            dataType = ParameterType.REQ;
        }

        if(name.indexOf('.') > 0) { //多级对象名称
            return new ApiParaHolder(name, true, dataType);
        } 
        return new ApiParaHolder(name, false, dataType);
    }
    
    public static ApiParaHolder[] parseHolders(String cfg) {
        if(StringUtil.isEmpty(cfg)) {
            return null;
        }

        String[] segs = StringUtil.split(cfg, PARA_SEPARATOR, QUOTATION_MARK, true);
        return parseHolders(segs);
    }
    
    public static ApiParaHolder[] parseHolders(String[] segs) {
        ApiParaHolder[] paras = new ApiParaHolder[segs.length];
        for(int i = 0; i < segs.length; i++) {
            paras[i] = parse(segs[i]);
        }
        return paras;
    }
    
    public static String takeStr(String s) {
        char ch = s.charAt(0);
        if (ch == QUOTATION_MARK || ch == '\'') { //单引号是为了兼容老版本
            int lastIdx = s.length() - 1;
            if(s.charAt(lastIdx) != ch) { //必须两个引号都打
                throw new InvalidParameterException("invalid string `" + s + "`");
            }
            return s.substring(1, lastIdx);
        }
        return s;
    }

    static class ConstParaHolder extends ApiParaHolder {
        public ConstParaHolder(String name) {
            super(name, false, ParameterType.BUILTIN);
        }

        public boolean isConst() {
            return true;
        }

        public String getAsString(AbsServerRequest req, Map<String, Object> respData) {
            return name;
        }
    }

    public static class StrParaHolder extends ConstParaHolder {
        public StrParaHolder(String name) {
            super(name);
        }

        public Object get(AbsServerRequest req, Map<String, Object> respData) {
            return name;
        }
    }
}
