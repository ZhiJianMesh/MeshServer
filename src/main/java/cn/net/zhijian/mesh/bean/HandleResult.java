package cn.net.zhijian.mesh.bean;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 处理结果，通常用于接口的返回，有时也作为内部函数的返回。
 * @author flyinmind of csdn.net
 *
 */
public final class HandleResult {
    public static final String CODE = "code";
    public static final String INFO = "info";
    public static final String DATA = "data";

    public final int code; //返回码
    public final String info; //错误信息，如果是OK，固定为Success
    public final Map<String, Object> data; //响应内容，可以为空
    public static final HandleResult OK = new HandleResult(RetCode.OK);
    public static final HandleResult NotExists = new HandleResult(RetCode.NOT_EXISTS);
    public static final HandleResult InternalError = new HandleResult(RetCode.INTERNAL_ERROR);
    public static final HandleResult ApiNotFound = new HandleResult(RetCode.API_NOT_FOUND);
    public static final HandleResult WrongJsonBody = new HandleResult(RetCode.WRONG_JSON_FORMAT);

    public HandleResult(int code, String info, Map<String, Object> data) {
        this.code = code;
        this.info = info == null || info.isEmpty() ? RetCode.getInfo(code) : info;
        if(data == null) {
            this.data = new HashMap<>();
        } else {
            this.data = data;
        }
    }
    
    public HandleResult(int code, Map<String, Object> data) {
        this(code, RetCode.getInfo(code), data);
    }
    
    public HandleResult(Map<String, Object> data) {
        this(RetCode.OK, RetCode.INFO_SUCCESS, data);
    }

    public HandleResult(int code, String info) {
        this(code, info, new HashMap<>());
    }
    
    public HandleResult(int code) {
        this(code, RetCode.getInfo(code), new HashMap<>());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> resp = new HashMap<>();
        resp.put(CODE, this.code);
        resp.put(INFO, this.info);
        if(this.data != null && !this.data.isEmpty()) {
            resp.put(DATA, this.data);
        }
        return resp;
    }

    public void put(String k, Object v) {
        data.put(k, v);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"").append(CODE).append("\":").append(code)
                .append(",\"").append(INFO).append("\":\"");

        if(this.info != null) { //info中不可以包括'\'与'"'
            sb.append(this.info);
        } else {
            sb.append(RetCode.INFO_UNKNOWN);
        }
        sb.append('\"');
        if(!this.data.isEmpty()) {
            sb.append(",\"").append(DATA).append("\":").append(JsonUtil.objToJson(data));
        }
        sb.append('}');
        return sb.toString();
    }
    
    /**
     * 只用于打印debug
     * @param max 最大字符串长度，或数组长度
     * @return 字符串
     */
    public String toString(int max) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{").append(CODE).append(':').append(RetCode.name(code))
                .append(',').append(INFO).append(":\"");

        if(this.info != null) { //info中不可以包括'\'与'"'
            sb.append(this.info);
        } else {
            sb.append(RetCode.INFO_UNKNOWN);
        }
        sb.append('\"');
        if(!this.data.isEmpty()) {
            sb.append(',').append(DATA).append(':');
            StringUtil.mapToStr(data, sb, max);
        }
        sb.append('}');
        return sb.toString();
    }
    
    public String brief() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append('{').append(CODE).append(":").append(RetCode.name(code))
                .append(",").append(INFO).append(":");

        if(this.info != null) {
            sb.append(this.info);
        } else {
            sb.append(RetCode.INFO_UNKNOWN);
        }
        sb.append("}");
        return sb.toString();
    }
    
    public static HandleResult fromStr(String s) {
        Map<String, Object> map = JsonUtil.jsonToMap(s);
        if(map == null || map.isEmpty()) {
            return new HandleResult(RetCode.INTERNAL_ERROR);
        }
        int resultCode = ValParser.getAsInt(map, CODE, RetCode.UNKNOWN_ERROR);
        String info = ValParser.getAsStr(map, INFO, RetCode.INFO_UNKNOWN);
        return new HandleResult(resultCode, info, ValParser.getAsObject(map, DATA));
    }
    
    public static HandleResult fromMap(Map<String, Object> map) {
        if(map == null || map.isEmpty()) {
            return new HandleResult(RetCode.INTERNAL_ERROR);
        }
        int resultCode = RetCode.parseCode(map.get(CODE), RetCode.UNKNOWN_ERROR);
        String info = ValParser.getAsStr(map, INFO, RetCode.INFO_UNKNOWN);
        return new HandleResult(resultCode, info, ValParser.getAsObject(map, DATA));
    }
    
    public static CompletableFuture<HandleResult> future(int code, String info) {
        return CompletableFuture.completedFuture(new HandleResult(code, info));
    }
    
    public static CompletableFuture<HandleResult> future(int code) {
        return CompletableFuture.completedFuture(new HandleResult(code));
    }

    public static CompletableFuture<HandleResult> future(HandleResult result) {
        return CompletableFuture.completedFuture(result);
    }
    
    public static CompletableFuture<HandleResult> future(Map<String, Object> data) {
        return CompletableFuture.completedFuture(new HandleResult(RetCode.OK, data));
    }
    
    public static CompletableFuture<HandleResult> future() {
        return CompletableFuture.completedFuture(HandleResult.OK);
    }
    
    /**
     * 如果字符串不以{}包裹，则返回null
     * 如果字符串以{}包裹，则尝试解析为map，
     * 如果code与info，则尝试解析为HandleResult，
     * 如果无code、info则解析为一个data，返回code为OK的HandleResult。
     * 
     * @param s json字符串
     * @return HandleResult
     */
    public static HandleResult tryParse(String s) {
        if((s == null || s.length() < 2)
           || (s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}')) {
            return null;
        }
        
        //如果用{}包裹起来，则认为是一个json体，直接返回结果
        //js速度非常慢，所以即使这里做了多余判断，也远比使用js高效几个数量级
        Map<String, Object> res = JsonUtil.jsonToMap(s);
        if(res == null) {
            return null;
        }
        
        Object o = res.get(HandleResult.CODE);
        //包含了code，返回的是整个HandleResult，否则只有data
        if(o != null) {
            try {
                int code = RetCode.parseCode(o); //code可能用了名称
                String info = ValParser.getAsStr(res, HandleResult.INFO, null);
                if(info == null) {
                    info = RetCode.getInfo(code);
                }
                res.put(HandleResult.CODE, code);
                Map<String, Object> data = ValParser.getAsObject(res, HandleResult.DATA);
                return new HandleResult(code, info, data); 
            } catch(Exception e) {
                return null;
            }
        }
        return new HandleResult(res);
    }
}
