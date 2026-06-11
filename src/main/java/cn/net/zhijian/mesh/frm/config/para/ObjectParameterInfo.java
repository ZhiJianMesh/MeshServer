package cn.net.zhijian.mesh.frm.config.para;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 对象类型，object配置项必须为一个复杂类型，
 * 否则ObjectParameterInfo将没有存在的意义
 * @author flyinmind
 *
 */
final class ObjectParameterInfo extends ParameterInfo {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String PROPERTY_PROPS = "props";
    private static final String PROPERTY_CHECKALL = "checkAll";

    private boolean checkAll = true; //列表的情况，每行都检查，建议在响应参数列表中设置为false
    private ParameterInfo[] segments = null;

    public ObjectParameterInfo() {}

    @Override
    protected boolean parseExt(Map<String, Object> cfg) {
        this.type = TYPE_OBJECT; //因为别名的原因，这里赋值一次，让类型名称保持一致
        if(!cfg.containsKey(PROPERTY_PROPS)) {
            this.checkAll = false;
            LOG.debug("No {} config items in object parameter `{}`,request will not check it", PROPERTY_PROPS, this.name);
            return true; //可以没有object定义，请求时只检查是否为一个map
        }

        List<Object> segs = ValParser.getAsList(cfg, PROPERTY_PROPS);
        if(segs == null || segs.isEmpty()) {
            LOG.error("Invalid segment define");
            return false;
        }

        this.segments = new ParameterInfo[segs.size()];
        //多行的情况下，才有是否检查每行的设置
        if(this.list) {
            this.checkAll = ValParser.getAsBool(cfg, PROPERTY_CHECKALL, true);
        }
        
        int num = segs.size();
        for(int i = 0; i < num; i++) {
            Map<String, Object> segCfg = ValParser.getAsObject(segs, i);
            if(segCfg == null) {
                LOG.error("Invalid segment define at {}", i);
                return false;
            }

            String name = ValParser.getAsStr(cfg, PROPERTY_NAME);
            if(StringUtil.isEmpty(name)) {
                LOG.error("Wrong parameter at {}, invalid name", i);
                return false;
            }
            try {
                ParameterInfo p = parse(segCfg, this.output);
                if(p == null) {
                    LOG.error("Wrong parameter {}, at {}", name, i);
                    return false;
                }
                segments[i] = p;
            } catch (Exception e) {
                LOG.error("Fail to parse segment parameter {}, at {}", name, i, e);
                return false;
            }
        }

        return true;
    }

    private Value getOne(AbsServerRequest req, Object ele, Map<String, Object> rootData) {
        if(segments == null || segments.length == 0) {
            return Value.success(false, ele); //知道是个结构体，但是不解析
        }
        
        boolean changed = false;
        if(ele instanceof Map<?, ?>) {
            Map<String, Object> data = ValParser.parseObject(ele);

            //输出时，只有在参数定义中的部分才可以返回，其他都忽略，所以需要新建一个
            Map<String, Object> res = this.output ? new HashMap<>() : data;
            for (ParameterInfo pi : segments) {
                Value v = pi.valueOf(req, rootData, data);
                if (!v.ok) {
                    return v;
                }
                
                if (v.changed) {
                    changed = true;
                }
                if(this.output) {
                    res.put(pi.name, v.v);
                } else if(v.changed) {
                    res.put(pi.name, v.v);
                }
            }
            return Value.success(changed, res);
        } else if(ele instanceof List<?>) {
            //无列名称的对象，以数组方式返回
            //按从0到n的顺序，逐个检查list中的对象，多余的抛弃
            List<Object> data = ValParser.parseList(ele);
            if(data == null || data.isEmpty()) {
                return Value.success(false, ele); //为空，直接返回
            }
            List<Object> res = new ArrayList<>();
            int idx = 0;
            for (ParameterInfo pi : segments) {
                Object oe = data.get(idx++);
                Value v = pi.valueOf(req, rootData, ValParser.parseObject(oe));
                if (!v.ok) {
                    return v;
                }
                if (v.changed) {
                    changed = true;
                }
                res.add(v.v);
            }
            return Value.success(changed, res);
        }
        
        return Value.failed(name + " invalid line format");
    }

    @Override
    protected Value getValue(AbsServerRequest req, Object ele, Map<String, Object> rootData, Map<String, Object> objData) {
        if (!this.list()) {
            return getOne(req, ele, rootData);
        }

        List<Object> l = ValParser.parseList(ele);
        if(l == null) {
            return Value.failed(name + " not a object list");
        }

        if (l.size() < this.minSize || l.size() > this.maxSize) {
            return Value.failed(name +" size must be >=" + this.minSize + " and <=" + this.maxSize);
        }

        if(!this.checkAll) { //无需检查每一行
            return Value.success(false, l);
        }
        
        boolean changed = false;
        int i = 0;
        Value v;
        List<Object> lines = new ArrayList<>(l.size());
        for (Object o : l) {
            v = getOne(req, o, rootData);
            if (v.ok) {
                lines.add(v.v);
                if(v.changed) {
                    changed = true;
                }
            } else {
                return Value.failed(name + " invalid object list@" + i + ','+ v.errInfo);
            }
            i++;
        }
        return Value.success(changed, lines);
    }

    @Override
    protected Object getDefault() {
        return null;
    }
    
    @Override
    public List<String> paras(String header) {
        List<String> l = super.paras(header);
        if(this.list) {
            return l; //不能处理列表的多级
        }
        List<String> l1 = new ArrayList<>(l);
        String h = header + '.' + this.name;
        for(ParameterInfo pi : segments) {
            l1.addAll(pi.paras(h));
        }
        return l1;
    }
}
