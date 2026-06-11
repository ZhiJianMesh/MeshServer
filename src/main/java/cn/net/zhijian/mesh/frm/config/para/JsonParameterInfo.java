package cn.net.zhijian.mesh.frm.config.para;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

/**
 * json参数，用于存储多个字段的参数，通常是一个Map或List。
 * 内部传递时使用Map、List对象，存储时使用json字符串。
 * 例如：存入数据库时转json，读到程序中，或返回给端侧时转为Map、List
 * @author flyinmind of csdn.net
 *
 */
final class JsonParameterInfo extends ParameterInfo {
    private static final Logger LOG = LogUtil.getInstance();
    private Object defaultVal = null;
    
    public JsonParameterInfo() {}
    
    @Override
    protected boolean parseExt(Map<String, Object> cfg) {
        this.type = TYPE_STRING; //因为别名的原因，这里赋值一次，让类型名称保持一致
        this.claim += "Json";
        
        if(!this.must) {
            this.claim += ";optional";
            if(cfg.containsKey(PROPERTY_DEFAULT)) {
                if(this.list) {
                    this.defaultVal = ValParser.getAsList(cfg, PROPERTY_DEFAULT);
                } else {
                    this.defaultVal = ValParser.getAsObject(cfg, PROPERTY_DEFAULT);
                }
            }
        }
        
        return true;
    }

    /**
     * 如果是字符串，则转为list或map，如果是map或list则转为string
     */
    @Override
    protected Value getValue(AbsServerRequest req, Object ele, Map<String, Object> rootData, Map<String, Object> objData) {
        if(output()) {
            if(!(ele instanceof String)) {
                return Value.failed(name + " output,value from db must be a string");
            }

            //容错
            String json = ValParser.parseString(ele);
            if (this.list()) {
                List<Object> list;
                try {
                    list = JsonUtil.jsonToList(json);
                } catch (Exception e) {
                    LOG.error("Fail to parse {}", json, e);
                    list = new ArrayList<>();
                }
                return Value.success(true, list);
            }

            Map<String, Object> map;
            try {
                map = JsonUtil.jsonToMap(json);
            } catch (Exception e) {
                LOG.error("Fail to parse {}", json, e);
                map = new HashMap<>();
            }
            return Value.success(true, map);
        }
        
        //输入参数，转为字符串
        if(this.list()) {
            if(!(ele instanceof List)) {
                return Value.failed("must be list");
            }
        } else if(!(ele instanceof Map)) {
            return Value.failed("must be map");
        }
        
        return Value.success(false, ele);
    }

    @Override
    protected Object getDefault() {
        return defaultVal;
    }
}
