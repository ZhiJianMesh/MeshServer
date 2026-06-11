package cn.net.zhijian.mesh.frm.config.para;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

final class PasswordParameterInfo extends ParameterInfo {
    private static final Logger LOG = LogUtil.getInstance();
    
    private static final String PROPERTY_EQUALSTO = "equalsTo"; //等于的参数名称，比如确认密码与密码
    private static final String PROPERTY_RULE = "rule"; //密码强度检查

    private int minLen = 0;
    private int maxLen = 1024;
    private Pattern regular = null;
    private String equalsTo = null;
    private int equalsToPos = -1; //字段命中点分隔符的位置，用于判断是否为多级参数引用
    private PwdChecker rule;

    public PasswordParameterInfo() {}

    @Override
    protected boolean parseExt(Map<String, Object> cfg) {
        this.maxLen = ValParser.getAsInt(cfg, PROPERTY_MAX, this.maxLen);
        this.minLen = ValParser.getAsInt(cfg, PROPERTY_MIN, this.minLen);
        this.claim += "Password,Length(" + this.minLen + "," + this.maxLen + ")";

        if(cfg.containsKey(PROPERTY_REGULAR)) {
            this.regular = Pattern.compile(ValParser.getAsStr(cfg, PROPERTY_REGULAR));
            this.claim += ';' + this.regular.toString();
        }

        if(!this.list()) {
            String s = ValParser.getAsStr(cfg, PROPERTY_EQUALSTO, "").trim();
            if(!StringUtil.isEmpty(s)) {
                this.equalsTo = s;
                this.equalsToPos = s.indexOf('.');
            }
        }

        String rule = ValParser.getAsStr(cfg, PROPERTY_RULE, null);
        if(rule != null) {
            PwdChecker r = PwdChecker.parse(rule);
            if(r != null) {
                this.rule = r;
            }
            return r != null;
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
            int len = val.length();
            if(len < this.minLen || len > this.maxLen) {
                return Value.failed(name + " invalid length");
            }
            
            if(this.regular != null && !this.regular.matcher(val).matches()) {
                return Value.failed(name + " fail to match regular");
            }

            if(this.equalsTo != null) { //密码的确认密码
                Object o;
                if(this.equalsToPos < 0) {
                    o = rootData.get(this.equalsTo);
                } else if(this.equalsToPos == 0) {
                    o = objData.get(this.equalsTo.substring(1));
                } else {
                    o = ValParser.getObject(rootData, this.equalsTo);//多级的
                }
                String s = o == null ? IConst.EMPTY_STR : ValParser.parseString(o);
                if(!s.equals(val)) {
                    return Value.failed(name + " not equals to " + this.equalsTo);
                }
            }
    
            if(this.rule != null) { //密码强度检查
                if(!this.rule.check(req, val)) {
                    return Value.failed(name + " invalid password");
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
        return null;
    }

    @Override
    public List<String> dependParas() {
        if(this.equalsTo != null && this.equalsToPos != 0) {
            return List.of(this.equalsTo); //对象中的本地字段不判断
        }
        return List.of();
    }
    
    private static class PwdChecker {
        private int minLen = 6;
        private int charTypeNum = 3;
        private int differentCharNum = 4;
        private String accountPara = null;
        
        boolean check(AbsServerRequest req, String pwd) {
            if(this.accountPara != null) {
                String acc = req.getString(this.accountPara);
                return SecureUtil.isPwdStrong(acc, pwd, minLen, charTypeNum, differentCharNum);
            }
            return SecureUtil.isPwdStrong(pwd, minLen, charTypeNum, differentCharNum);
        }
        
        //minLen,charTypeNum,differentCharNum,accountPara
        static PwdChecker parse(String cfg) {
            PwdChecker checker = new PwdChecker();
            String[] ss = cfg.split("[,;]");
            if(ss.length > 0) {
                int i = 0;
                try {
                    checker.minLen = Integer.parseInt(ss[i++]);
                    checker.charTypeNum = i < ss.length ? Integer.parseInt(ss[i++]) : checker.charTypeNum;
                    checker.differentCharNum = i < ss.length ? Integer.parseInt(ss[i++]) : checker.differentCharNum;
                    checker.accountPara = i < ss.length ? ss[i] : checker.accountPara;
                } catch(NumberFormatException ne) {
                    LOG.error("Invalid {} config `{}`", PROPERTY_RULE, cfg, ne);
                    return null;
                }
            }
            if(checker.minLen <= 0 || checker.charTypeNum <= 0 || checker.differentCharNum <= 0) {
                LOG.error("Invalid {} config `{}`", PROPERTY_RULE, cfg);
                return null;
            }
            return checker;
        }
    }
}
