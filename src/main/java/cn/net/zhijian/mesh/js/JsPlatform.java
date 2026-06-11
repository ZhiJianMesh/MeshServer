package cn.net.zhijian.mesh.js;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.quickjs.JavascriptMethod;

/**
 * 提供js中日志处理相关的接口
 * @author flyinmind of csdn.net
 *
 */
public final class JsPlatform {
    @JavascriptMethod
    public boolean isSupported(String feature) {
        return false;
    }

    /**
     * 构建版本，不一定会引起引擎版本变更
     * @return 构建版本
     */
    @JavascriptMethod
    public String buildVersion() {
        return "Java_" + IConst.BUILDVERSION;
    }

    /**
     * 引擎是支撑业务代码运行的平台
     * 引擎版本不变，则业务逻辑不用改变
     * @return 引擎版本
     */
    @JavascriptMethod
    public String engineVersion() {
        return IConst.ENGINEVERSION;
    }
}
