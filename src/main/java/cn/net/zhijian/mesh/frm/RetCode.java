package cn.net.zhijian.mesh.frm;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.NoSuchElementException;

import cn.net.zhijian.util.ValParser;

/**
 * 返回码，主要用于接口调用时的返回码，有时也会用于内部异步处理时的返回码
 * @author flyinmind of csdn.net
 *
 */
public final class RetCode {
    public static final String INFO_SUCCESS = "Success";
    public static final String INFO_UNKNOWN = "Unknown error";

    public static final int OK = 0;
    public static final int DEPRECATED = 1;
    public static final int EXECUTING = 2; //服务执行中，不是错误

    //1000以内，内部处理问题
    public static final int INTERNAL_ERROR = 100;
    public static final int HTTP_ERROR = 101;
    public static final int INVALID_TOKEN = 102; //token无效
    public static final int EMPTY_BODY = 103;
    public static final int DB_ERROR = 104;
    public static final int INVALID_SESSION = 105; //会话错误
    public static final int SERVICE_NOT_FOUND = 106; //服务不存在
    public static final int TOO_BUSY = 107;
    public static final int SYSTEM_TIMEOUT = 108;
    public static final int NOT_SUPPORTED_FUNCTION = 109; //API存在，但是所需的功能不支持
    public static final int API_NOT_FOUND = 110; //API不存在
    public static final int NO_RIGHT = 111; //无权限
    public static final int NO_NODE = 112; //没有合适的节点
    public static final int INVALID_NODE = 113; //不合适的节点
    public static final int THIRD_PARTY_ERR = 114; //第三方服务错误
    public static final int PAST_DUE = 115; //欠费
    public static final int LOOP_OVER = 116; //循环终止，只用在循环接口中
    public static final int LOGIC_FALSE = 117; //逻辑判断失败
    public static final int FORBIDDEN = 118; //禁止访问

    public static final int UNKNOWN_ERROR = 150;

    //2000+ 已知的业务相关错误
    public static final int EXISTS = 2000;
    public static final int NOT_EXISTS = 2001;

    //3000+ API问题
    public static final int API_ERROR = 3000;
    public static final int WRONG_JSON_FORMAT = 3001;
    public static final int INVALID_VERSION = 3002;
    public static final int DATA_WRONG = 3003;

    //4000+ 参数问题，4001表示第一个参数，以此类推
    public static final int WRONG_PARAMETER = 4000;

    //5000+ 服务问题
    public static final int SERVICE_ERROR = 5000;
    public static final int INVALID_STATE = 5001;

    //客户端处理出了问题，由客户端自己定义
    public static final int CLIENT_ERROR = 100000;
    public static final int NO_OPERATION = 200000; //没有任何可以执行的操作，只用于服务侧
    
    private static final HashMap<Integer, String> descriptions = new HashMap<>();
    private static final HashMap<String, Integer> defines = new HashMap<>();
    private static final HashMap<Integer, String> names = new HashMap<>();

    static {
        initDefines();
        initDescriptions();
    }

    public static String getInfo(int code) {
        String info = descriptions.get(code);
        return info == null ? INFO_UNKNOWN : info;
    }

    /**
     * 将RetCode中的所有返回码转为js的常量
     * @return 返回js形式的RetCode对象
     */
    public static String retCodeObjJs() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("const RetCode={");
        int i = 0;
        for (Field field : RetCode.class.getDeclaredFields()) {
            int modifiers = field.getModifiers(); 
            if(Modifier.isStatic(modifiers)
               && Modifier.isFinal(modifiers)
               && Modifier.isPublic(modifiers)
               && field.getType().equals(int.class)) {
                String name = field.getName();
                try {
                    int v = field.getInt(RetCode.class);
                    if(i > 0) {
                        sb.append(',');
                    }
                    sb.append(name).append(':').append(v);
                    i++;
                } catch (IllegalArgumentException | IllegalAccessException e) {
                }
            }
        }
        sb.append('}');
        return sb.toString();
    }
    
    private static void initDefines() {
        for (Field field : RetCode.class.getDeclaredFields()) {
            int modifiers = field.getModifiers(); 
            if(Modifier.isStatic(modifiers)
               && Modifier.isFinal(modifiers)
               && Modifier.isPublic(modifiers)
               && field.getType().equals(int.class)) {
                String name = field.getName();
                try {
                    int v = field.getInt(RetCode.class);
                    defines.put("RetCode." + name, v);
                    defines.put(name, v); //带.与不带，都支持
                    names.put(v, name);
                } catch (IllegalArgumentException | IllegalAccessException e) {
                }
            }
        }
    }
    
    private static void initDescriptions() {
        descriptions.put(OK, INFO_SUCCESS);
        descriptions.put(DEPRECATED, "Deprecated");
        descriptions.put(EXECUTING, "Excuting");

        descriptions.put(EXISTS, "Already exists");
        descriptions.put(NOT_EXISTS, "Not exists");

        descriptions.put(API_ERROR, "Api error");
        descriptions.put(HTTP_ERROR, "Http error");
        descriptions.put(LOGIC_FALSE, "Logic false");
        descriptions.put(FORBIDDEN, "request has been denied");        

        descriptions.put(INVALID_SESSION, "Invalid session");
        descriptions.put(DB_ERROR, "Database failed");
        descriptions.put(WRONG_JSON_FORMAT, "Wrong formatted json data");
        descriptions.put(SERVICE_NOT_FOUND, "Fail to parse service dns");
        descriptions.put(INVALID_VERSION, "Invalid version used");
        descriptions.put(NO_RIGHT, "No right");
        descriptions.put(NO_NODE, "No valid node");
        descriptions.put(INVALID_NODE, "Request an invalid node");
        descriptions.put(THIRD_PARTY_ERR, "Third part error");
        descriptions.put(PAST_DUE, "Balance unsufficient");
        
        descriptions.put(UNKNOWN_ERROR, INFO_UNKNOWN);
        descriptions.put(DATA_WRONG, "Inner data wrong");

        descriptions.put(TOO_BUSY, "System too busy");
        descriptions.put(NOT_SUPPORTED_FUNCTION, "Function not supported");
        descriptions.put(API_NOT_FOUND, "Api not found");
        descriptions.put(SYSTEM_TIMEOUT, "System timeout");
        descriptions.put(INTERNAL_ERROR, "Internal error");
        descriptions.put(EMPTY_BODY, "Request body is empty");
        descriptions.put(INVALID_TOKEN, "Invalid token");
        
        descriptions.put(WRONG_PARAMETER, "Wrong parameter");
        descriptions.put(SERVICE_ERROR, "Service error");
        descriptions.put(INVALID_STATE, "Wrong state");
        descriptions.put(CLIENT_ERROR, "Client handle error");
    }
    
    public static String name(int v) {
        String s = names.get(v);
        return s == null ? Integer.toString(v) : s;
    }
    
    public static int parseCode(Object code, int def) {
        if(code == null) {
            return def;
        }
        if(code instanceof Integer) {
            return (int)code;
        }
        String sCode = String.valueOf(code);
        if(sCode.matches("\\d+")) {
            int c = ValParser.parseInt(sCode, -1);
            if(c >= RetCode.OK) {
                return c;
            }
        } else {
            Integer c = defines.get(sCode);
            if(c != null) { //防止获得空的Integer，在变成int时抛出空指针异常
                return c;
            }
        }
        return def;
    }
    
    public static int parseCode(Object code) {
        if(code == null) {
            throw new NullPointerException();
        }
        if(code instanceof Integer) {
            return (int)code;
        }
        String sCode = String.valueOf(code);
        if(sCode.matches("\\d+")) {
            int c = ValParser.parseInt(code, -1);
            if(c >= RetCode.OK) {
                return c;
            }
        } else {
            Integer c = defines.get(sCode);
            if(c != null) { //防止获得空的Integer，在变成int时抛出空指针异常
                return c;
            }
        }

        throw new NoSuchElementException(code + " not exists");
    }

    public static List<Integer> parseCodes(List<Object> codeList) {
        if(codeList == null || codeList.isEmpty()) {
            return null;
        }
        List<Integer> codes = new ArrayList<>(codeList.size());
        for(Object o : codeList) {
            codes.add(parseCode(o));
        }
        return codes;
    }
}
