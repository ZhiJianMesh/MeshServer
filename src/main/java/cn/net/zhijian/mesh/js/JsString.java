package cn.net.zhijian.mesh.js;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.quickjs.JavascriptMethod;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.GovUtil;
import cn.net.zhijian.util.IPUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * js字符串处理函数
 * @author flyinmind of csdn.net
 *
 */
public final class JsString {
    @JavascriptMethod
    public String uuid() {
        return StringUtil.base64UUID();
    }

    /**
     * js处理long时，有精度损失，所以转成string
     * @param s 字符串列表
     * @return hash值，一个大于0的长整型字符串
     */
    @JavascriptMethod
    public String longHash(String s) {
        return Long.toString(StringUtil.longHashCode(s));
    }

    @JavascriptMethod
    public String absHash(String s) {
        return Long.toString(ValParser.absLong(StringUtil.longHashCode(s)));
    }

    @JavascriptMethod
    public int intHash(String s) {
        return StringUtil.concatHashCode(s);
    }

    @JavascriptMethod
    public int hash(String s) {
        return s.hashCode();
    }

    @JavascriptMethod
    public boolean chkCreditCode(String code) {
        return GovUtil.isValidCreditCode(code);
    }
    
    @JavascriptMethod
    public boolean chkIdNo(String code) {
        return GovUtil.isValidIdNo(code);
    }

    @JavascriptMethod
    public int base64CharCode(String c) {
        return ByteUtil.getBase64CharVal(c.charAt(0));
    }

    @JavascriptMethod
    public String base64Char(int v) { 
        return IConst.EMPTY_STR + ByteUtil.getBase64CharByVal(v);
    }

    @JavascriptMethod
    public boolean isLanIP(String ip) {
        return IPUtil.isLanIp(ip);
    }

    @JavascriptMethod
    public boolean isIPv4(String ip) {
        return IPUtil.isValidIPv4(ip);
    }

    @JavascriptMethod
    public boolean isIPv6(String ip) {
        return IPUtil.isValidIPv6(ip);
    }
}
