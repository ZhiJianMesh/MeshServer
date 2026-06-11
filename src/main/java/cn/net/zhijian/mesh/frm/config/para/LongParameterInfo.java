package cn.net.zhijian.mesh.frm.config.para;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.ValParser;

/**
 * 长整型参数
 * @author flyinmind of csdn.net
 *
 */
final class LongParameterInfo extends NumericParameterInfo {
    private long min = Long.MIN_VALUE;
    private long max = Long.MAX_VALUE;
    private Long defaultVal = null;

    public LongParameterInfo() {}
    
    @Override
    protected boolean parseExt(Map<String, Object> cfg) {
        super.parseExt(cfg);
        this.max = ValParser.getAsLong(cfg, PROPERTY_MAX, this.max);
        this.min = ValParser.getAsLong(cfg, PROPERTY_MIN, this.min);

        if(!this.must) {
            if(cfg.containsKey(PROPERTY_DEFAULT)) {
                this.defaultVal = ValParser.getAsLong(cfg, PROPERTY_DEFAULT);
            }
        }
        this.claim = "long,between " +  min + " and " + max;

        return true;
    }

    private Value getOne(AbsServerRequest req, Object ele, Map<String, Object> rootData, Map<String, Object> objData) {
        long v;
        boolean changed = false;

        if(ele instanceof Number) {
            v = ((Number)ele).longValue();
        } else {
            try {
                v = Long.parseLong(ele.toString());
                changed = true;
            } catch(Exception e) {
                return Value.failed(name + " fail to parse long string");
            }
        }

        if(v < this.min || v > this.max) {
            return Value.failed(name + " must be >=" + this.min + " and <=" + this.max);
        }
        
        if(this.smallerThan != null) {
            long l = parseValue(getSmallerThan(rootData, objData), Long.MIN_VALUE);
            if(v >= l) {
                return Value.failed(name + " not smaller than " + this.smallerThan);
            }
        }
        if(this.biggerThan != null) {
            long l = parseValue(getBiggerThan(rootData, objData), Long.MAX_VALUE);
            if(v <= l) {
                return Value.failed(name + " not bigger than " + this.biggerThan);
            }
        }
        return Value.success(changed, v);
    }

    @Override
    protected Value getValue(AbsServerRequest req, Object ele, Map<String, Object> rootData, Map<String, Object> objData) {
        if (!this.list()) {
            return getOne(req, ele, rootData, objData);
        }

        if(!(ele instanceof List)) {
            return Value.failed(name + " not a long list");
        }

        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>)ele;
        if (l.size() < this.minSize || l.size() > this.maxSize) {
            return Value.failed(name + " size must be >=" + this.minSize + " and <=" + this.maxSize);
        }

        List<Long> ss = new ArrayList<>(l.size());
        int i = 0;
        for (Object o : l) {
            i++;
            Value v = getOne(req, o, rootData, objData);
            if (v.ok) {
                ss.add((Long)v.v);
            } else {
                return Value.failed(name + " invalid long list@" + i + ',' + v.errInfo);
            }
        }
        return Value.success(true, ss);
    }

    @Override
    protected Object getDefault() {
        return defaultVal;
    }
    
    private static long parseValue(Object o, long def) {
        if(o == null) {
            return def;
        }
        return ValParser.parseLong(o, def);
    }
}
