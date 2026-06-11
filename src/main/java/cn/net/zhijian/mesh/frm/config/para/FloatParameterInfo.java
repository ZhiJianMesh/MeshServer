package cn.net.zhijian.mesh.frm.config.para;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.ValParser;

final class FloatParameterInfo extends NumericParameterInfo {
    private float min = -Float.MAX_VALUE;
    private float max = Float.MAX_VALUE;
    private Object defaultVal = null;

    public FloatParameterInfo() {}

    @Override
    protected boolean parseExt(Map<String, Object> cfg) {
        super.parseExt(cfg);
        this.max = ValParser.getAsFloat(cfg, PROPERTY_MAX, this.max);
        this.min = ValParser.getAsFloat(cfg, PROPERTY_MIN, this.min);

        if(!this.must) {
            if(cfg.containsKey(PROPERTY_DEFAULT)) {
                if(this.list()) {
                    List<Float> fl = new ArrayList<>();
                    List<Object> oo = ValParser.getAsList(cfg, PROPERTY_DEFAULT);
                    if(oo != null) {
                        for(Object o : oo) {
                            fl.add(ValParser.parseFloat(o, 0));
                        }
                    }
                    this.defaultVal = fl;
                } else {
                    this.defaultVal = ValParser.getAsFloat(cfg, PROPERTY_DEFAULT);
                }
            }
        }
        this.claim = "float,between " +  min + " and " + max;

        return true;
    }

    private Value getOne(AbsServerRequest req, Object ele, Map<String, Object> rootData, Map<String, Object> objData) {
        float v;
        boolean changed = false;

        if(ele instanceof Number) {
            v = ((Number)ele).floatValue();
        } else {
            try {
                v = Float.parseFloat(ele.toString());
                changed = true;
            } catch(Exception e) {
                return Value.failed(name + " fail to parse float string");
            }
        }

        if(v < this.min || v > this.max) {
            return Value.failed(name + " must be >=" + this.min + " and <=" + this.max);
        }
        
        if(this.smallerThan != null) {
            float f = parseValue(getSmallerThan(rootData, objData), Float.MIN_VALUE);
            if(v >= f) {
                return Value.failed(name + " not smaller than " + this.smallerThan);
            }
        }
        
        if(this.biggerThan != null) {
            float f = parseValue(getBiggerThan(rootData, objData), Float.MAX_VALUE);
            if(v <= f) {
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
            return Value.failed(name + " not a float list");
        }

        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>)ele;
        if (l.size() < super.minSize || l.size() > super.maxSize) {
            return Value.failed(name + " size must be >=" + this.minSize + " and <=" + this.maxSize);
        }

        List<Float> ss = new ArrayList<>(l.size());
        int i = 0;
        for (Object o : l) {
            i++;
            Value v = getOne(req, o, rootData, objData);
            if (v.ok) {
                ss.add((Float)v.v);
            } else {
                return Value.failed(name + " invalid float list@" + i + ',' + v.errInfo);
            }
        }
        return Value.success(true, ss);
    }

    @Override
    protected Object getDefault() {
        return defaultVal;
    }
    
    private static float parseValue(Object o, float def) {
        if(o == null) {
            return def;
        }
        return ValParser.parseFloat(o, def);
    }
}
