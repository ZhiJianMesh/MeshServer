package cn.net.zhijian.mesh.frm.config.para;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.ValParser;

final class DoubleParameterInfo extends NumericParameterInfo {
    private double min = -Double.MAX_VALUE;
    private double max = Double.MAX_VALUE;
    private Double defaultVal = null;

    public DoubleParameterInfo() {}

    @Override
    protected boolean parseExt(Map<String, Object> cfg) {
        super.parseExt(cfg);
        this.max = ValParser.getAsDouble(cfg, PROPERTY_MAX, this.max);
        this.min = ValParser.getAsDouble(cfg, PROPERTY_MIN, this.min);
        if(!this.must) {
            if(cfg.containsKey(PROPERTY_DEFAULT)) {
                this.defaultVal = ValParser.getAsDouble(cfg, PROPERTY_DEFAULT);
            }
        }
        this.claim = "Double,between " +  min + " and " + max;

        return true;
    }

    /**
     * 
     * @param req 请求体
     * @param ele 当前参数
     * @return 转变后的值
     */
    private Value getOne(AbsServerRequest req, Object ele, Map<String, Object> rootData, Map<String, Object> objData) {
        double v;
        boolean changed = false;

        if(ele instanceof Number) {
            v = ((Number)ele).doubleValue();
        } else {
            try {
                v = Double.parseDouble(ele.toString());
                changed = true;
            } catch(Exception e) {
                return Value.failed(name + " fail to parse double string");
            }
        }

        if(v < this.min || v > this.max) {
            return Value.failed("must be >=" + this.min + " and <=" + this.max);
        }

        if(this.smallerThan != null) {
            double d = parseValue(getSmallerThan(rootData, objData), Double.MIN_VALUE);
            if(v >= d) {
                return Value.failed("not smaller than " + this.smallerThan);
            }
        }

        if(this.biggerThan != null) {
            double d = parseValue(getBiggerThan(rootData, objData), Double.MAX_VALUE);
            if(v <= d) {
                return Value.failed("not bigger than " + this.biggerThan);
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
            return Value.failed(name + " not a float list");
        }

        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>)ele;
        if (l.size() < super.minSize || l.size() > super.maxSize) {
            return Value.failed(name + " size must be >=" + this.minSize + " and <=" + this.maxSize);
        }

        List<Double> ss = new ArrayList<>(l.size());
        int i = 0;
        for (Object o : l) {
            i++;
            Value v = getOne(req, o, rootData, objData);
            if (v.ok) {
                ss.add((Double)v.v);
            } else {
                return Value.failed(name + " invalid double list@" + i + ',' + v.errInfo);
            }
        }
        return Value.success(true, ss);
    }

    @Override
    protected Object getDefault() {
        return defaultVal;
    }
    
    private static double parseValue(Object o, double def) {
        if(o == null) {
            return def;
        }
        return ValParser.parseDouble(o, def);
    }
}
