package cn.net.zhijian.mesh.frm.config.para;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.IPUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

final class IPParameterInfo extends ParameterInfo {
    private static final Logger LOG = LogUtil.getInstance();
    
    private static final String PROPERTY_FORMAT  = "format";

    private Object defaultVal = null;

    private int ipFormat = IPUtil.FORMAT.NONE.v;

    public IPParameterInfo() {}

    @Override
    protected boolean parseExt(Map<String, Object> cfg) {
        if(!this.must) {
            if(cfg.containsKey(PROPERTY_DEFAULT)) {
                if (this.list()) {
                    List<String> sl = new ArrayList<>();
                    List<Object> oo = ValParser.getAsList(cfg, PROPERTY_DEFAULT);
                    if(oo != null) {
                        for (Object o : oo) {
                            sl.add(ValParser.parseString(o, IConst.EMPTY_STR));
                        }
                    }
                    this.defaultVal = sl;
                } else {
                    this.defaultVal = ValParser.getAsStr(cfg, PROPERTY_DEFAULT);
                }
            }
        }
        
        String ipFormat = ValParser.getAsStr(cfg, PROPERTY_FORMAT, null);
        if(ipFormat != null) {
            this.ipFormat = IPUtil.FORMAT.IP.v;
            String[] ss = ipFormat.toUpperCase().split("\\|");
            if(StringUtil.isInArray(IPUtil.FORMAT.LAN.name(), ss)) {
                this.ipFormat += IPUtil.FORMAT.LAN.v;
            }
            if(StringUtil.isInArray(IPUtil.FORMAT.WAN.name(), ss)) {
                this.ipFormat += IPUtil.FORMAT.WAN.v;
            }
            if((this.ipFormat & (IPUtil.FORMAT.WAN.v + IPUtil.FORMAT.LAN.v)) == IPUtil.FORMAT.WAN.v + IPUtil.FORMAT.LAN.v) {
                LOG.error("Invalid {} setted, LAN,WAN can't be set at the same time", PROPERTY_FORMAT);
                return false;
            }
            if(StringUtil.isInArray(IPUtil.FORMAT.V6.name(), ss)) {
                this.ipFormat += IPUtil.FORMAT.V6.v;
            }
            if(StringUtil.isInArray(IPUtil.FORMAT.V4.name(), ss)) {
                this.ipFormat += IPUtil.FORMAT.V4.v;
            }
            if((this.ipFormat & (IPUtil.FORMAT.V4.v + IPUtil.FORMAT.V6.v)) == IPUtil.FORMAT.V4.v + IPUtil.FORMAT.V6.v) {
                LOG.error("Invalid {} setted, V4,V6 can't be set at the same time", PROPERTY_FORMAT);
                return false;
            }
            if(StringUtil.isInArray(IPUtil.FORMAT.PORT.name(), ss)) {
                this.ipFormat += IPUtil.FORMAT.PORT.v;
            }
            if(StringUtil.isInArray(IPUtil.FORMAT.LIST.name(), ss)) {
                this.ipFormat += IPUtil.FORMAT.LIST.v;
            }
        }

        if(!this.must) {
            this.claim += ";optional";
        }

        return true;
    }

    private Value getOne(AbsServerRequest req, Object ele/*NotNull*/, Map<String, Object> rootData, Map<String, Object> objData) {
        String val;
        if (ele instanceof String) {
            val = (String)ele;
        } else {
            val = ele.toString();
        }

        if(!output()) {//输出参数不做严格的判断与转换操作
            if(this.ipFormat != IPUtil.FORMAT.NONE.v) { //是IP地址
                IPUtil.FORMAT errFmt;
                if((this.ipFormat & IPUtil.FORMAT.LIST.v) != 0) {
                    errFmt = IPUtil.isValidIPs(val, this.ipFormat);
                } else {
                    errFmt = IPUtil.isValidIP(val, this.ipFormat);
                }
                if(errFmt != IPUtil.FORMAT.NONE) {
                    return Value.failed(name + " invalid ip because of " + errFmt.name());
                }
            }
        }

        return Value.success(false, val);
    }

    @Override
    protected Value getValue(AbsServerRequest req, Object ele, Map<String, Object> rootData, Map<String, Object> objData) {
        if (!this.list()) {
            return getOne(req, ele, rootData, objData);
        }

        if(!(ele instanceof List)) {
            return Value.failed(name + " not a string list");
        }

        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>)ele;
        if (l.size() < this.minSize || l.size() > this.maxSize) {
            return Value.failed(name + " size must be >=" + this.minSize + " and <=" + this.maxSize);
        }
        int i = 0;
        List<String> ss = new ArrayList<>(l.size());
        for (Object o : l) {
            i++;
            Value v = getOne(req, o, rootData, objData);
            if (v.ok) {
                ss.add((String)v.v);
            } else {
                return Value.failed(name + " invalid string list@" + i + ',' + v.errInfo);
            }
        }
        return Value.success(true, ss);
    }

    @Override
    protected Object getDefault() {
        return defaultVal;
    }
}
