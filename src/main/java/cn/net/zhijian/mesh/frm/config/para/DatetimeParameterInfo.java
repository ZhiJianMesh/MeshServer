package cn.net.zhijian.mesh.frm.config.para;

import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

final class DatetimeParameterInfo extends NumericParameterInfo {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String PROPERTY_FORMAT = "format";
    private static final String PROPERTY_UNIT = "unit";

    private static final String INNER_FORMAT = "yyyy-MM-dd HH:mm:ss";

    private long min;
    private long max;
    private int unit = 1; //“1”表示多少毫秒
    private String format = null;
    private Long defaultVal = null;

    public DatetimeParameterInfo() {
        if(this.output) {
            this.min = 0;
        } else {
            this.min = parseFromString("1970-01-01 00:00:00", INNER_FORMAT).getTime();
        }
        this.max = parseFromString("9999-01-01 00:00:00", INNER_FORMAT).getTime();
    }

    @Override
    protected boolean parseExt(Map<String, Object> cfg) {
        this.type = TYPE_DATE; //因为别名的原因，这里赋值一次，让类型名称保持一致
        this.format = ValParser.getAsStr(cfg, PROPERTY_FORMAT, INNER_FORMAT);
        this.unit = ValParser.getAsInt(cfg, PROPERTY_UNIT, 1);
        java.util.Date dt;
        String str;

        if(!this.must) {
            if(cfg.containsKey(PROPERTY_DEFAULT)) {
                str = ValParser.getAsStr(cfg, PROPERTY_DEFAULT);
                dt = parseFromString(str, this.format);
                if(dt == null) {
                    LOG.error("Wrong default date format:{}", str);
                    return false;
                }
                this.defaultVal = dt.getTime();
            }
        }

        if(cfg.containsKey(PROPERTY_MIN)) {
            str = ValParser.getAsStr(cfg, PROPERTY_MIN);
            dt = parseFromString(str, this.format);
            if(dt == null) {
                LOG.error("Wrong min date format:{}", str);
                return false;
            }
            this.min = dt.getTime();
        }

        if(cfg.containsKey(PROPERTY_MAX)) {
            str = ValParser.getAsStr(cfg, PROPERTY_MAX);
            dt = parseFromString(str, this.format);
            if(dt == null) {
                LOG.error("Wrong max date format:{}", str);
                return false;
            }
            this.max = dt.getTime();
        }
        
        Date begin = new Date(this.min);
        Date end = new Date(this.max);
        
        this.claim = "Between '" +  toString(begin, INNER_FORMAT)
                     + " and '" + toString(end, INNER_FORMAT)
                     + ",format " + (this.format == null ? "null" : this.format)
                     + ",default is " + (this.defaultVal != null ? toString(new Date(this.defaultVal), INNER_FORMAT) : "null");
        return super.parseExt(cfg);
    }

    @Override
    protected Object getDefault() {
        return defaultVal;
    }

    private Value getOne(Object ele, Map<String, Object> rootData, Map<String, Object> objData) {
        long v;
        boolean changed = false;
        if(ele instanceof Number) {
            v = ((Number)ele).longValue();
        } else {
            try {
                v = Long.parseLong(ele.toString());
                changed = true;
            } catch(Exception e) {
                return Value.failed(name + " fail to parse datetime string");
            }
        }

        if(this.unit != 1) {
            v *= this.unit;
            changed = true;
        }

        if(v < this.min || v > this.max) {
            return Value.failed(name + " must be >=" + this.min + " and <=" + this.max);
        }

        if(this.output) {
            if(this.format != null) {
                //如果设置了服务端解析格式，则按服务端时区返回格式化后的字符串
                String dt = DateUtil.utcToLocale(v, this.format);
                return Value.success(true, dt);
            }
        }
        
        if(this.smallerThan != null) {
            long d = parseValue(getSmallerThan(rootData, objData), Long.MIN_VALUE);
            if(v >= d) {
                return Value.failed("not smaller than " + this.smallerThan);
            }
        }
        
        if(this.biggerThan != null) {
            long d = parseValue(getBiggerThan(rootData, objData), Long.MAX_VALUE);
            if(v <= d) {
                return Value.failed("not bigger than " + this.biggerThan);
            }
        }
        
        return Value.success(changed, v);
    }

    /**
     * 只能以long(UTC)形式输入，存入数据库时，也必须为long(UTC)
     * 查询时，直接返回UTC，在端侧格式化为本地时间
     * @param req 请求对象
     * @param ele 参数对象
     * @param rootData 请求数据map结构的根部，用于取得关联参数进行判断，比如equalsTo、smallerThan、biggerThan等
     * @param objData 请求数据某个对象对应的map，第一层中与rootData相同
     * @return 返回解析后的值
     */
    @Override
    protected Value getValue(AbsServerRequest req, Object ele, Map<String, Object> rootData, Map<String, Object> objData) {
        if (!this.list()) {
            return getOne(ele, rootData, objData);
        }

        if(!(ele instanceof List)) {
            return Value.failed(name + " not a datetime list");
        }

        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>)ele;
        if (l.size() < super.minSize || l.size() > super.maxSize) {
            return Value.failed(name + " size must be >=" + this.minSize + " and <=" + this.maxSize);
        }

        List<Long> ss = new ArrayList<>(l.size());
        int i = 0;
        for (Object o : l) {
            i++;
            Value v = getOne(o, rootData, objData);
            if (v.ok) {
                ss.add((long)v.v);
            } else {
                return Value.failed(name + " invalid double list@" + i + ',' + v.errInfo);
            }
        }
        return Value.success(true, ss);
    }
    
    public static String toString(java.util.Date date, String format) {
        SimpleDateFormat fmt = new SimpleDateFormat(format);
        return fmt.format(date);
    }

    public static Date fromString(String val, SimpleDateFormat fmt) {
        try {
            java.util.Date dt = fmt.parse(val);
            if(dt == null) {
                return null;
            }
            return new Date(dt.getTime());
        } catch(Exception e) {
            LOG.error("Wrong date format", e);
        }
        return null;
    }

    public static java.util.Date parseFromString(String val, String format) {
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        return fromString(val, sdf);
    }

    private static long parseValue(Object o, long def) {
        if(o == null) {
            return def;
        }
        return ValParser.parseLong(o, def);
    }
}
