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
            this.biggerPos = s.indexOf('.');
            if(this.biggerPos == 0) { //最前面一个`.`表示从顶层对象获取
                this.biggerThan = s.substring(1).trim();
            } else {
                this.biggerThan = s; //可能从当前对象获取，也可能从多级对象中获取
            }
        }
        s = ValParser.getAsStr(cfg, PROPERTY_SMALLERTHAN, "").trim();
        if(!StringUtil.isEmpty(s)) {
            this.smallerPos = s.indexOf('.'); //减少运行时的判断
            if(this.smallerPos == 0) {
                this.smallerThan = s.substring(1).trim();
            } else {
                this.smallerThan = s;
            }
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
        if(this.biggerPos == 0) { //第一个是`.`，从顶层对象获取
            return rootData.get(this.biggerThan);
        }
        if(this.biggerPos < 0) { //无`.`，从当前对象获取，如果不是object型参数，rootData与objData相同
            return objData.get(this.biggerThan);
        }
        return ValParser.getObject(rootData, this.biggerThan);//多级的
    }
    
    protected Object getSmallerThan(Map<String, Object> rootData, Map<String, Object> objData) {
        if(this.smallerPos == 0) { //第一个是`.`，从顶层对象获取
            return rootData.get(this.smallerThan);
        }
        if(this.smallerPos < 0) { //无`.`，从当前对象获取，如果不是object型参数，rootData与objData相同
            return objData.get(this.smallerThan);
        }
        return ValParser.getObject(rootData, this.smallerThan);//多级的
    }
    
    @Override
    public List<String> dependParas() {
        List<String> l = new ArrayList<>();
        if(this.biggerThan != null && this.biggerPos <= 0) {
            l.add(this.biggerThan); //只判断顶层字段
        }
        if(this.smallerThan != null && this.smallerPos <= 0) {
            l.add(this.smallerThan); //只判断顶层字段
        }
        return l;
    }
}
