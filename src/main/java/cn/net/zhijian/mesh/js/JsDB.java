package cn.net.zhijian.mesh.js;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.quickjs.JavascriptMethod;
/**
 * js中sql拼接，本质上就是返回一个字符串。
 * 可以直接在js中完成，也可以通过sql、action等完成
 * @author flyinmind of csdn.net
 *
 */
public final class JsDB {
    @JavascriptMethod
    public String sql(String s) {
        return s; //统一在这里过一遍，防止后面需要对sql进行编辑
    }

    @JavascriptMethod
    public String sqlError(int code, String info) {
        //返回一个json体，此处只作为普通字符串返回，在java中解析
        return "{\"" + HandleResult.CODE + "\":" + code
                + ",\"" + HandleResult.INFO + "\":\"" + info +"\"}";
    }
}
