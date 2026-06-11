package cn.net.zhijian.mesh.frm.config.para;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 参数抽象类，所有参数类型需要有此类派生
 * @author flyinmind of csdn.net
 *
 */
public abstract class ParameterInfo {
    private static final Logger LOG = LogUtil.getInstance();

    public static final String TYPE_OBJECT = "OBJECT";
    public static final String TYPE_STRING = "STRING";
    public static final String TYPE_IP = "IP";
    public static final String TYPE_PASSWORD = "PASSWORD";
    public static final String TYPE_INT = "INT";
    public static final String TYPE_LONG = "LONG";
    public static final String TYPE_BOOL = "BOOL";
    public static final String TYPE_DATE = "DATE";
    public static final String TYPE_DOUBLE = "DOUBLE";
    public static final String TYPE_FLOAT = "FLOAT";
    public static final String TYPE_BYTES = "BYTES";
    public static final String TYPE_JSON = "JSON";

    public static final String PROPERTY_MAX  = "max";        //最大值
    public static final String PROPERTY_MIN  = "min";        //最小值
    public static final String PROPERTY_MAXSIZE = "maxSize"; //为list时，最大长度
    public static final String PROPERTY_MINSIZE = "minSize"; //为list时，最小长度
    public static final String PROPERTY_NAME = "name";       //字段名称
    public static final String PROPERTY_TYPE = "type";       //类型
    public static final String PROPERTY_OPTIONS = "options"; //取值范围
    public static final String PROPERTY_MAPS = "maps";       //取值映射
    public static final String PROPERTY_MUST = "must";       //是否必须
    public static final String PROPERTY_LOG = "log";         //是否计入日志
    public static final String PROPERTY_LIST = "list";       //是否为列表
    public static final String PROPERTY_CONST = "const";     //是否为常数
    public static final String PROPERTY_DEFAULT = "default"; //默认值
    public static final String PROPERTY_REGULAR  = "regular";
    public static final String PROPERTY_BIGGERTHAN = "biggerThan"; //大于某个其他参数
    public static final String PROPERTY_SMALLERTHAN = "smallerThan"; //小于某个其他参数
    
    protected String name = IConst.EMPTY_STR;
    protected String claim = IConst.EMPTY_STR;

    protected String type = IConst.EMPTY_STR;

    protected boolean must = true; //是否为必须输入的参数
    protected boolean list = false;
    protected boolean output = false; //是否为输出，在Datetime类型时有用
    protected boolean isConst = false; //是否为常数

    protected int maxSize = 10240;
    protected int minSize = 0;

    protected boolean log = true; //在accesslog中是否打印
    
    /**
     * @param cfg 配置信息
     * @return 解析是否成功，成为为true
     */
    protected abstract boolean parseExt(Map<String, Object> cfg);

    /**
     * 返回相应类型的默认值，没有，则返回null
     * @return 默认值
     */
    protected abstract Object getDefault();

    /**
     * 
     * 从请求参数中解析参数
     * @param req 请求信息
     * @param ele 参数值
     * @param rootData 请求数据map结构的根部，用于取得关联参数进行判断，比如equalsTo、smallerThan、biggerThan等
     * @param objData 请求数据某个对象对应的map，第一层中与rootData相同
     * @return 解析后的参数值
     */
    protected abstract Value getValue(AbsServerRequest req, Object ele, Map<String, Object> rootData, Map<String, Object> objData);

    private static final Map<String, Class<? extends ParameterInfo>> ParaTypes = new HashMap<>();
    static {
        ParaTypes.put(TYPE_STRING, StringParameterInfo.class);
        ParaTypes.put("STR", StringParameterInfo.class);
        ParaTypes.put(TYPE_IP, IPParameterInfo.class);
        ParaTypes.put(TYPE_PASSWORD, PasswordParameterInfo.class);
        ParaTypes.put(TYPE_JSON, JsonParameterInfo.class);
        ParaTypes.put(TYPE_BOOL, BoolParameterInfo.class);
        ParaTypes.put("BOOLEAN", BoolParameterInfo.class);
        ParaTypes.put(TYPE_INT, IntParameterInfo.class);
        ParaTypes.put(TYPE_FLOAT, FloatParameterInfo.class);
        ParaTypes.put(TYPE_DOUBLE, DoubleParameterInfo.class);
        ParaTypes.put(TYPE_LONG, LongParameterInfo.class);
        ParaTypes.put(TYPE_DATE, DatetimeParameterInfo.class);
        ParaTypes.put("DATETIME", DatetimeParameterInfo.class);
        ParaTypes.put(TYPE_BYTES, ByteArrayParameterInfo.class);
        ParaTypes.put(TYPE_OBJECT, ObjectParameterInfo.class);
        ParaTypes.put("OBJ", ObjectParameterInfo.class);
    }

    /**
     * 解析参数对象，可以解析请求与响应字段
     * @param cfg 配置内容
     * @param output 是否为响应字段
     * @return 参数信息
     */
    public static ParameterInfo parse(Map<String, Object> cfg, boolean output) {
        if(!cfg.containsKey(PROPERTY_NAME) || !cfg.containsKey(PROPERTY_TYPE)) {
            return null;
        }

        ParameterInfo p;
        String type = ValParser.getAsStr(cfg, PROPERTY_TYPE).toUpperCase();
        String name = ValParser.getAsStr(cfg, PROPERTY_NAME);
        Class<? extends ParameterInfo> cls = ParaTypes.get(type);
        if(cls == null) {
            LOG.error("Wrong parameter type '{}' in parameter {}", type, name);
            return null;
        }

        try {
            p = cls.getConstructor().newInstance();
            p.type = type;
            p.name = name;
            p.must = ValParser.getAsBool(cfg, PROPERTY_MUST, p.must);
            p.list = ValParser.getAsBool(cfg, PROPERTY_LIST, p.list);
            p.output = output;

            if(p.list) {
                p.maxSize = ValParser.getAsInt(cfg, PROPERTY_MAXSIZE, p.maxSize);
                p.minSize = ValParser.getAsInt(cfg, PROPERTY_MINSIZE, 0);
            }

            p.log = ValParser.getAsBool(cfg, PROPERTY_LOG, p.log);

            if(!p.parseExt(cfg)) {
                LOG.error("Fail to parse extension of parameter {}", p.name);
                return null; 
            }
            p.isConst = ValParser.getAsBool(cfg, PROPERTY_CONST, p.isConst);
            if(p.isConst) {
                if(p.getDefault() == null) {
                    LOG.error("No default value for const parameter {}", p.name);
                    return null; 
                }
                p.must = false;
            }
            
            return p;
        } catch (Exception e) {
            LOG.error("Fail to parse parameter {}, type={}", name, type, e);
            return null;
        }
    }
    
    /**
     * 参数名称
     * @return 参数名称
     */
    public String name() {
        return name;
    }

    /**
     * 参数类型的名称，比如STRING/INT等
     * @return 类型
     */
    public String type() {
        return type;
    }
    
    /**
     * 是否可以写入日志
     * @return 是否可写入日志
     */
    public boolean logable() {
        return log;
    }
    
    /**
     * 参数是否必须的
     * @return 是否必须
     */
    public boolean must() {
        return must;
    }

    /**
     * 是否为输出，在Datetime时使用，
     * 且，是输出参数时，不对参数做严格的判断
     * @return 是否为输出参数
     */
    public boolean output() {
        return output;
    }

    /**
     * 是否为一个列表
     * @return 是否为list
     */
    public boolean list() {
        return list;
    }
    
    /**
     * 参数的简要描述
     * @return 简要说明
     */
    public String claim() {
        return claim;
    }

    /**
     * 从请求参数或响应内容中解析字段
     * @param req 请求信息
     * @param rootData 数据根部
     * @param objData 数据中对象的根部
     * @return 解析后的参数值
     */
    public final Value valueOf(AbsServerRequest req, Map<String, Object> rootData, Map<String, Object> objData) {
        if(this.isConst) { //常数的情况，即使传入了参数，也必须返回default值
            return Value.success(true, this.getDefault());
        }
        Object ele = objData.get(this.name);
        if (ele == null) {
            if (this.must) {
                return Value.failed(name + " is null");
            }

            Object defaultVal = this.getDefault(); //常数的情况，default必不为空
            if(defaultVal != null) {
                return Value.success(true, defaultVal);
            }
            return Value.none();
        }

        return getValue(req, ele, rootData, objData);
    }

    /**
     * 添加自定义参数类型解析器，必须在AbstractInitializer子类的beforeInit中调用，
     * 因为随后将是配置解析，需要用到参数类型
     * @param name 参数类型名称
     * @param parser 参数解析器
     */
    public static void register(String name, Class<? extends ParameterInfo> parser) {
        ParaTypes.put(name.trim().toUpperCase(), parser);
    }
    
    @Override
    public String toString() {
        return "{name:" + name + ",type:" + type + '}';
    }
    
    /**
     * 生成在参数列表中的名称
     * 解决object中多级的问题
     * @return 参数列表中的名称
     */
    public List<String> paras(String header) {
        return List.of(header + this.name);
    }
    
    /**
     * 依赖的参数列表
     * @return 参数列表中的名称
     */
    public List<String> dependParas() {
        return List.of();
    }
    
    public static class Builder {
        private final Map<String, Object> cfg = new HashMap<>();
        private final boolean isOutput;
        private final String name;
        private final String type;
        
        public Builder(String name, String type, boolean must, boolean isOutput) {
            cfg.put(ParameterInfo.PROPERTY_NAME, name);
            cfg.put(ParameterInfo.PROPERTY_TYPE, type);
            cfg.put(ParameterInfo.PROPERTY_MUST, must);
            this.name = name;
            this.type = type;
            this.isOutput = isOutput;
        }
        
        public Builder(String name, String type, boolean must) {
            this(name, type, must, false);
        }
        
        public Builder(String name, String type) {
            this(name, type, true, false);
        }
        
        public Builder asList() {
            cfg.put(ParameterInfo.PROPERTY_LIST, true);
            return this;
        }
        
        public Builder setRegular(String regular) {
            if(!type.equals(TYPE_STRING) || StringUtil.isEmpty(regular)) {
                LOG.warn("Invalid regular in parameter {}", name);
                return this;
            }
            cfg.put(ParameterInfo.PROPERTY_REGULAR, regular);
            return this;
        }
        
        public Builder setOptions(List<Object> opts) {
            if(opts == null || opts.isEmpty()) {
                LOG.warn("Invalid options in parameter {}", name);
                return this;
            }
            cfg.put(ParameterInfo.PROPERTY_OPTIONS, opts);
            return this;
        }
        
        public Builder setMax(Object max) {
            cfg.put(ParameterInfo.PROPERTY_MAX, max);
            return this;
        }
        
        public Builder setMin(Object min) {
            cfg.put(ParameterInfo.PROPERTY_MIN, min);
            return this;
        }
        
        public Builder setDefault(Object def) {
            if(def == null) {
                LOG.warn("Invalid default value in parameter {}", name);
                return this;
            }
            cfg.put(ParameterInfo.PROPERTY_DEFAULT, def);
            return this;
        }
        
        public Builder setMaxSize(int max) {
            cfg.put(ParameterInfo.PROPERTY_MAXSIZE, max);
            return this;
        }
        
        public Builder setMinSize(int min) {
            cfg.put(ParameterInfo.PROPERTY_MINSIZE, min);
            return this;
        }
        
        public ParameterInfo build() {
            return ParameterInfo.parse(cfg, isOutput);
        }
    }
}
