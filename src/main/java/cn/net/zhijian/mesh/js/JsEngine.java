package cn.net.zhijian.mesh.js;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.quickjs.JSObject;
import cn.net.zhijian.quickjs.QuickJSContext;
import cn.net.zhijian.quickjs.QuickJSLogger;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

/**
 * quick js只能在单个线程中执行，所以使用ThreadLocal保证每个线程中使用的实例是不同的。
 * 因为本系统的所有api都是独立的，不需要在不同api间共享状态，所以这种处理没有问题。
 * @author flyinmind of csdn.net
 */
public final class JsEngine {
    private static final Logger LOG = LogUtil.getInstance();

    private static final JsMesh jsMesh = new JsMesh();
    private static final JsLogger jsLogger = new JsLogger();
    private static final JsString jsString = new JsString();
    private static final JsSecure jsSecure = new JsSecure();
    private static final JsPlatform jsPlatform = new JsPlatform();
    private static final JsDB jsDB = new JsDB();
    
    private static final ThreadLocal<QuickJSContext> threadContext = new ThreadLocal<>();
    
    public static void initContext() {
        QuickJSContext ctx = threadContext.get(); //每个线程的js会话是分开的
        if(ctx != null) {
            return;
        }
        QuickJSLogger.setLogger(jsLogger);
        ctx = QuickJSContext.create(); //无法在其他线程中关闭，所以只能等到程序停止自动释放
        ctx.setMaxStackSize(4 * 1024 * 1024);
        ctx.evaluate(RetCode.retCodeObjJs());
        JSObject jsObj = ctx.getGlobalObject();
        jsObj.setJavaObject("Mesh", jsMesh);
        jsObj.setJavaObject("Logger", jsLogger);
        jsObj.setJavaObject("JStr", jsString);
        jsObj.setJavaObject("Secure", jsSecure);
        jsObj.setJavaObject("Platform", jsPlatform);
        jsObj.setJavaObject("DB", jsDB);
        threadContext.set(ctx);
    }
    
    public static void destroyContext() {
        QuickJSContext ctx = threadContext.get(); //每个线程的js会话是分开的
        if(ctx != null) {
            ctx.close();
            threadContext.set(null);
        }
    }

    
    /**
     * 为了兼容android上的quickjs，如果脚本返回对象，其实返回的是json字符串，
     * 然后在java中转为Map
     * @param js 待执行js脚本
     * @return 返回字符串
     */
    public static String getString(String js) {
        QuickJSContext ctx = threadContext.get();
        try {
            Object o = ctx.evaluate(js);
            return ValParser.parseString(o);
        } catch(Exception e) {
            LOG.error("Fail to execute js script {}", js, e);
            return null;
        }
    }

    public static int getInt(String js) {
        QuickJSContext ctx = threadContext.get();
        try {
            Object o = ctx.evaluate(js);
            return ValParser.parseInt(o, 0);
        } catch(Exception e) {
            LOG.error("Fail to execute js script {}", js, e);
            return 0;
        }
    }

    public static boolean getBool(String js) {
        QuickJSContext ctx = threadContext.get();
        try {
            Object o = ctx.evaluate(js);
            return ValParser.parseBool(o, false);
        } catch(Exception e) {
            LOG.error("Fail to execute js script {}", js, e);
            return false;
        }
    }
    
    public static HandleResult getHandleResult(String js) {
        QuickJSContext ctx = threadContext.get();
        try {
            Object o = ctx.evaluate(js);
            if(!(o instanceof String)) {
                LOG.error("script return invalid value, {}", js);
                return new HandleResult(RetCode.INTERNAL_ERROR, "invalid result");
            }
            return HandleResult.fromStr((String)o);
        } catch(Exception e) {
            LOG.error("Fail to execute script {}", js, e);
            return new HandleResult(RetCode.INTERNAL_ERROR, "js exception");
        }
    }
}
