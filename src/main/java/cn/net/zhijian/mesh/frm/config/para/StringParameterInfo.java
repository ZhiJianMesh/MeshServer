package cn.net.zhijian.mesh.frm.config.para;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.client.KeyStoreClient;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

final class StringParameterInfo extends ParameterInfo {
    private static final Logger LOG = LogUtil.getInstance();
    
    private static final String PROPERTY_CODEMODE = "codeMode";
    private static final String PROPERTY_LEN      = "len";
    private static final String PROPERTY_TRIM     = "trim";
    private static final String PROPERTY_CASE     = "case";
    private static final String PROPERTY_TAIL     = "tail";
    private static final String PROPERTY_KEYNAME  = "keyName"; //加解密的秘钥名称
    private static final String PROPERTY_KEYTIME  = "keyTime"; //密钥最大有效期，默认1年，单位天

    private int minLen = 0;
    private int maxLen = 100 * 1024;
    private Object defaultVal = null;
    private Pattern regular = null;
    private String tail = null;

    private String keyName = null;
    private long keyTime = KeyStoreClient.DEFAULT_INTERVAL; // ms

    private boolean trim = false;  //是否去除首尾空格
    private Map<String, String> maps = null;
    private Set<String> options = null; //即使在选项很少的情况下，Set也要比数组快一个数量级

    private enum CodeMode{NONE, ENCODE, DECODE}
    private enum CaseMode{NONE, UPPER, LOWER}

    private CodeMode codeMode = CodeMode.NONE;
    private CaseMode caseMode = CaseMode.NONE;

    public StringParameterInfo() {}

    @Override
    protected boolean parseExt(Map<String, Object> cfg) {
        this.type = TYPE_STRING; //因为别名的原因，这里赋值一次，让类型名称保持一致
        this.claim = "String," + (!this.must ? ";optional" : "") + ",length";
        int len = ValParser.getAsInt(cfg, PROPERTY_LEN, -1);
        if(len > 0) {
            this.maxLen = len;
            this.minLen = len;
            this.claim += ":" + len;
        } else {
            this.maxLen = ValParser.getAsInt(cfg, PROPERTY_MAX, this.maxLen);
            this.minLen = ValParser.getAsInt(cfg, PROPERTY_MIN, this.minLen);
            this.claim += "(" + this.minLen + "," + this.maxLen + ")";
        }
        
        if(cfg.containsKey(PROPERTY_REGULAR)) {
            this.regular = Pattern.compile(ValParser.getAsStr(cfg, PROPERTY_REGULAR));
            this.claim += ';' + this.regular.toString();
        }

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

        if(cfg.containsKey(PROPERTY_MAPS)) {
            this.maps = new HashMap<>();
            Map<String, Object> maps = ValParser.getAsObject(cfg, PROPERTY_MAPS);
            if(maps != null) {
                for (Map.Entry<String, Object> e : maps.entrySet()) {
                    this.maps.put(e.getKey(), ValParser.parseString(e.getValue(), IConst.EMPTY_STR));
                }
            }
        }

        List<String> opts = ValParser.getAsStrList(cfg, PROPERTY_OPTIONS);
        if(opts != null && !opts.isEmpty()) {
            this.options = new HashSet<>(opts);
        }

        String codeMode = ValParser.getAsStr(cfg, PROPERTY_CODEMODE, CodeMode.NONE.name()).toUpperCase();
        try {
            this.codeMode = CodeMode.valueOf(codeMode);
        } catch(Exception e) {
            LOG.error("Invalid codeMode setted", e);
            return false;
        }
        if(this.codeMode == CodeMode.ENCODE) {
            this.keyTime = ValParser.getAsLong(cfg, PROPERTY_KEYTIME, 366) * 86400 * 1000;
            if(this.keyTime < KeyStoreClient.MIN_INTERVAL) {
                this.keyTime = KeyStoreClient.MIN_INTERVAL;
            }
        }

        String caseMode = ValParser.getAsStr(cfg, PROPERTY_CASE, CodeMode.NONE.name()).toUpperCase();
        try {
            this.caseMode = CaseMode.valueOf(caseMode);
        } catch(Exception e) {
            LOG.error("Invalid caseMode setted", e);
            return false;
        }

        //设置了加解密，且没有设置log，则log默认为false
        if(this.codeMode != CodeMode.NONE) {
            this.keyName = ValParser.getAsStr(cfg, PROPERTY_KEYNAME, null);
            if(StringUtil.isEmpty(keyName)) {
                LOG.error("Invalid {} setted, must be set when need encode or decode", PROPERTY_KEYNAME);
                return false;
            }
            this.log = false;
        }

        this.trim = ValParser.getAsBool(cfg, PROPERTY_TRIM, this.trim);
        this.tail = ValParser.getAsStr(cfg, PROPERTY_TAIL, null);

        return true;
    }

    private Value getOne(AbsServerRequest req, Object ele/*NotNull*/, Map<String, Object> rootData, Map<String, Object> objData) {
        String val;
        if (ele instanceof String) {
            val = (String)ele;
        } else {
            val = ele.toString();
        }

        if (this.codeMode == CodeMode.DECODE) { //需要解码的情况，不做后继判断
            val = KeyStoreClient.decode(req, this.keyName, val);
            return Value.success(true, val);
        }

        boolean changed = false;
        if(!output()) { //输出参数不做严格的判断与转换操作
            int len = val.length();
            if(len < this.minLen || len > this.maxLen) {
                return Value.failed(name + " invalid length");
            }

            if (this.trim) { //如果设置了映射，最好自己做trim
                val = val.trim();
                changed = true;
            }

            if(this.regular != null && !this.regular.matcher(val).matches()) {
                return Value.failed(name + " fail to match regular");
            }

            if (this.options != null) {
                if (!this.options.contains(val)) {
                    return Value.failed(val + " not in options of " + name);
                }
            }

            if(this.maps != null) {
                if (this.maps.containsKey(val)) {
                    val = this.maps.get(val);
                    changed = true;
                }
            }

            if(this.tail != null) {
                val += this.tail;
                changed = true;
            }
        }

        if (this.codeMode == CodeMode.ENCODE) { //需要加密的数据，放在最后进行
            changed = true;
            val = KeyStoreClient.encode(req, this.keyName, this.keyTime, val);
        }

        if(val != null) {
            if (this.caseMode == CaseMode.UPPER) { //改变大小写
                changed = true;
                val = val.toUpperCase();
            } else if (this.caseMode == CaseMode.LOWER) {
                changed = true;
                val = val.toLowerCase();
            }
        }

        return Value.success(changed, val);
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
