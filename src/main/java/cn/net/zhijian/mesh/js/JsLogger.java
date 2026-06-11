package cn.net.zhijian.mesh.js;

import org.slf4j.Logger;

import cn.net.zhijian.quickjs.JavascriptMethod;
import cn.net.zhijian.quickjs.QuickJSLogger;
import cn.net.zhijian.util.LogUtil;

/**
 * 提供js中日志处理相关的接口
 * @author flyinmind of csdn.net
 *
 */
public final class JsLogger extends QuickJSLogger {
    private static final Logger LOG = LogUtil.getInstance();

    @JavascriptMethod
    @Override
    public void debug(String fmt, Object... args) {
        LOG.debug(fmt, args);
    }

    @JavascriptMethod
    @Override
    public void info(String fmt, Object... args) {
        LOG.info(fmt, args);
    }

    @JavascriptMethod
    @Override
    public void warn(String fmt, Object... args) {
        LOG.warn(fmt, args);
    }

    @JavascriptMethod
    @Override
    public void error(String fmt, Object... args) {
        LOG.error(fmt, args);
    }
}
