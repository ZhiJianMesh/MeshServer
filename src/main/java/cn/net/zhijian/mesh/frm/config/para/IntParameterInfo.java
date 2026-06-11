package cn.net.zhijian.mesh.frm.config.para;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.ValParser;

final class IntParameterInfo extends NumericParameterInfo {
    private int min = Integer.MIN_VALUE;
    private int max = Integer.MAX_VALUE;
    private Object defaultVal = null;
    private Set<Integer> options = null;

    public IntParameterInfo() {}
    
    @Override
    protected boolean parseExt(Map<String, Object> cfg) {
        super.parseExt(cfg);
        this.max = ValParser.getAsInt(cfg, PROPERTY_MAX, this.max);
        this.min = ValParser.getAsInt(cfg, PROPERTY_MIN, this.min);

        if(!this.must) {
            if(cfg.containsKey(PROPERTY_DEFAULT)) {
                if (this.list()) {
                    List<Integer> sl = new ArrayList<>();
                    List<Object> oo = ValParser.getAsList(cfg, PROPERTY_DEFAULT);
                    if(oo != null) {
                        for (Object o : oo) {
                            sl.add(ValParser.parseInt(o, 0));
                        }
                    }
                    this.defaultVal = sl;
                } else {
                    this.defaultVal = ValParser.getAsInt(cfg, PROPERTY_DEFAULT);
                }
            }
        }
        
        List<Object> opts = ValParser.getAsList(cfg, PROPERTY_OPTIONS);
        if(opts != null && !opts.isEmpty()) {
            this.options = new HashSet<>();
            for (Object opt : opts) {
                this.options.add(ValParser.parseInt(opt, 0));
            }
        }
        
        this.claim = "int,between " +  min + " and " + max;

        return true;
    }

    private Value getOne(AbsServerRequest req, Object ele, Map<String, Object> rootData, Map<String, Object> objData) {
        int v;
        boolean changed = false;

        if(ele instanceof Number) {
            v = ((Number)ele).intValue();
        } else {
            try {
                v = Integer.parseInt(ele.toString());
                changed = true;
            } catch(Exception e) {
                return Value.failed(name + " fail to parse int " + ele.toString());
            }
        }

        if(v < this.min || v > this.max) {
            return Value.failed(name + " must be >=" + this.min + " and <=" + this.max);
        }
        
        if (this.options != null) {
            if (!this.options.contains(v)) {
                return Value.failed(name + " not in options");
            }
        }
        
        if(this.smallerThan != null) {
            int i = parseValue(getSmallerThan(rootData, objData), Integer.MIN_VALUE);
            if(v >= i) {
                return Value.failed(name + " not smaller than " + this.smallerThan);
            }
        }

        if(this.biggerThan != null) {
            int i = parseValue(getBiggerThan(rootData, objData), Integer.MAX_VALUE);
            if(v <= i) {
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
            return Value.failed(name + " not a int list");
        }

        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>)ele;
        if (l.size() < this.minSize || l.size() > this.maxSize) {
            return Value.failed(name + " size must be >=" + this.minSize + " and <=" + this.maxSize);
        }

        List<Integer> ss = new ArrayList<>(l.size());
        int i = 0;
        for (Object o : l) {
            i++;
            Value v = getOne(req, o, rootData, objData);
            if (v.ok) {
                ss.add((Integer)v.v);
            } else {
                return Value.failed(name + " invalid int list@" + i + ',' + v.errInfo);
            }
        }
        return Value.success(true, ss);
    }

    @Override
    protected Object getDefault() {
        return defaultVal;
    }
    
    private static int parseValue(Object o, int def) {
        if(o == null) {
            return def;
        }
        return ValParser.parseInt(o, def);
    }
}
