package cn.net.zhijian.mesh.frm.config.para;

import java.util.Map;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.ValParser;

/**
 * byte[]，只检查长度，存数据库时使用base64
 * @author flyinmind
 *
 */
final class ByteArrayParameterInfo extends ParameterInfo {
    private int minLen = 0;
    private int maxLen = 1024 * 1024;
    private byte[] defaultVal = null;
    
    public ByteArrayParameterInfo() {}

    @Override
    protected boolean parseExt(Map<String, Object> cfg) {
        this.maxLen = ValParser.getAsInt(cfg, PROPERTY_MAX, this.maxLen);
        this.minLen = ValParser.getAsInt(cfg, PROPERTY_MIN, this.minLen);
        if(!this.must) {
            String s = ValParser.getAsStr(cfg, PROPERTY_DEFAULT, null);
            if(s != null) {
                this.defaultVal = ByteUtil.base642bin(s);
            }
        }
        this.claim = "ByteArray, size between " + minLen + " and " + maxLen;
        
        return true;
    }

    @Override
    protected Value getValue(AbsServerRequest req, Object ele, Map<String, Object> rootData, Map<String, Object> objData) {
        String s = ValParser.parseString(ele, IConst.EMPTY_STR);
        if(!output()) {
            int l = (s.length() * 6) >> 3; //size after base64, *6/8
            if (l > this.maxLen || l < this.minLen) {
                return Value.failed(name + " invalid length");
            }
        }

        return Value.success(true, ByteUtil.base642bin(s));
    }

    @Override
    protected Object getDefault() {
        return defaultVal;
    }
}
