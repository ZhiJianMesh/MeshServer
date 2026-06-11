package cn.net.zhijian.mesh.frm.config.para;

import java.util.Map;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.ValParser;

final class BoolParameterInfo extends ParameterInfo {
    private Boolean defaultVal = null;
    
    public BoolParameterInfo() {}

    protected boolean parseExt(Map<String, Object> cfg) {
        if(!this.must) {
            if(cfg.containsKey(PROPERTY_DEFAULT)) {
                this.defaultVal = ValParser.getAsBool(cfg, PROPERTY_DEFAULT);
            }
        }
        
        return true;
    }
    
    @Override
    public Value getValue(AbsServerRequest req, Object ele, Map<String, Object> rootData, Map<String, Object> objData) {
        if(ele instanceof Boolean) {
            return Value.success(false, ele);
        }

        try {
            boolean v = false;
            if (ele instanceof Number) {
                v = (((Number) ele).intValue() != 0);
            } else if (ele instanceof String) {
                String s = (String)ele;
                v = s.equals("1") || s.equalsIgnoreCase("true");
            }
            return Value.success(true, v);
        } catch(Exception e) {
            return Value.failed(name + " fail to parse boolean");
        }
    }

    @Override
    protected Object getDefault() {
        return defaultVal;
    }
}
