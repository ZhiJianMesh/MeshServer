package cn.net.zhijian.mesh.frm.config.para;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

abstract class NumericParameterInfo extends ParameterInfo {
    protected String biggerThan = null;
    protected String smallerThan = null;
    private int biggerPos = -1;
    private int smallerPos = -1;
    
    @Override
    protected boolean parseExt(Map<String, Object> cfg) {
        String s = ValParser.getAsStr(cfg, PROPERTY_BIGGERTHAN, "").trim();
        if(!StringUtil.isEmpty(s)) {
            this.biggerThan = s;
            this.biggerPos = s.indexOf('.');
        }
        s = ValParser.getAsStr(cfg, PROPERTY_SMALLERTHAN, "").trim();
        if(!StringUtil.isEmpty(s)) {
            this.smallerThan = s;
            this.smallerPos = s.indexOf('.'); //减少运行时的判断
        }
        return true;
    }
    
    /**
     * 从数据中获取biggerThan所指定字段的值
     * @param rootData 数据的根部
     * @param objData 数据中某个对象的根部
     * @return 值
     */
    protected Object getBiggerThan(Map<String, Object> rootData, Map<String, Object> objData) {
        if(this.biggerPos < 0) { //没有指定多级，直接请求
            return rootData.get(this.biggerThan);
        }
        if(this.biggerPos == 0) { //对象或对象数组中一个字段
            return objData.get(this.biggerThan.substring(1));
        }
        return ValParser.getObject(rootData, this.biggerThan);//多级的
    }
    
    protected Object getSmallerThan(Map<String, Object> rootData, Map<String, Object> objData) {
        if(this.smallerPos < 0) { //没有指定多级，直接请求
            return rootData.get(this.smallerThan);
        }
        if(this.smallerPos == 0) { //对象或对象数组中一个字段
            return objData.get(this.smallerThan.substring(1));
        }
        return ValParser.getObject(rootData, this.smallerThan);//多级的
    }
    
    @Override
    public List<String> dependParas() {
        List<String> l = new ArrayList<>();
        if(this.biggerThan != null && this.biggerPos != 0) {
            l.add(this.biggerThan); //对象中的本地字段不判断
        }
        if(this.smallerThan != null && this.smallerPos != 0) {
            l.add(this.smallerThan); //对象中的本地字段不判断
        }
        return l;
    }
}
