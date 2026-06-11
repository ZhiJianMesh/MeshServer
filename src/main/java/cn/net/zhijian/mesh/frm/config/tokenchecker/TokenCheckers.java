package cn.net.zhijian.mesh.frm.config.tokenchecker;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.frm.abs.AbsTokenChecker;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.util.LogUtil;

public class TokenCheckers implements IOAuth {
    private static final Logger LOG = LogUtil.getInstance();

    private static final Map<String, AbsTokenChecker> BUILTIN_CHECKERS = new HashMap<>();
    public static final BACKEND Backend = new BACKEND();
    public static final APP Om = new APP(OM_TOKEN_CHECKER, SERVICE_OM);
    public static final MNT Mnt = new MNT(Om, Backend);
    public static final APP AppAll = new APP(APP.SERVICE_ANY);
    public static final APP App = new APP();

    static {
        //加载所有内置的TokenChecker
        addChecker(Om);
        addChecker(Mnt);
        addChecker(App);
        addChecker(new INIT(App, Om, Backend));
        addChecker(new OAUTH());
        addChecker(new COMPANY());
        //base-user，不检查外网接入权限
        addChecker(new BASEUSER(UNIUSER_TOKEN_CHECKER, SERVICE_UNIUSER));
        //uni-user，不检查外网接入权限，因为一定从外网访问
        addChecker(new BASEUSER(BASEUSER_TOKEN_CHECKER, SERVICE_USER));
        //user，要检查是否有外网接入权限
        addChecker(new USER());
        addChecker(Backend);
    }

    private static synchronized void addChecker(AbsTokenChecker tc) {
        BUILTIN_CHECKERS.put(tc.name, tc);
    }

    /**
     * @param name 名称
     * @return TokenChecker
     */
    public static AbsTokenChecker getChecker(String name) {
        String upperName = name.replaceAll("\\s+", IConst.EMPTY_STR).toUpperCase();
        AbsTokenChecker tc = BUILTIN_CHECKERS.get(upperName);
        if(tc != null) {
            return tc;
        }

        int pos = name.indexOf(AbsTokenChecker.TOKEN_NAME_SPLITER); //APP|APPS|NODE-调用方服务名称
        synchronized(BUILTIN_CHECKERS) {
            if(pos > 0) {
                String services = name.substring(pos + 1).toLowerCase();//服务名全小写
                
                if(!APP.SERVICE_ANY.equals(services)
                    && !services.matches("[_0-9a-z]{1,30}(,[_0-9a-z]{1,30})*")) {
                    throw new IllegalArgumentException("services list error:" + services);
                }
                
                String sortedName = sortedName(name.substring(0, pos), services);
                if(upperName.startsWith(APP_TOKEN_CHECKER)) {
                    AbsTokenChecker checker = BUILTIN_CHECKERS.get(sortedName);
                    if(!(checker instanceof APP)) {
                        checker = new APP(services); //指定调用方服务名称
                        BUILTIN_CHECKERS.put(sortedName, checker); //名称中有服务名
                    }
                    return checker;
                }
                LOG.error("Invalid token checker:{}", name);
                return null;
            } else {
                tc = BUILTIN_CHECKERS.get(upperName);
                if(tc != null) { //双重检查，防止别的线程已初始化
                    return tc;
                }
            }
            /*
             * tokenChecker在HttpServer中使用，
             * 不容许外部应用使用自定义的token检查机制，防止随意跳过token检查。
             * 所以不用区分是否为builtin，不使用ServiceInfo的classLoader加载
             */
            try {
                AbsTokenChecker tokenChecker = (AbsTokenChecker)Class.forName(name).getConstructor().newInstance();
                BUILTIN_CHECKERS.put(tokenChecker.name.toUpperCase(), tokenChecker);
                return tokenChecker;
            } catch (Exception e) {
                LOG.error("Fail to load token checker {}", name, e);
                return null;
            }
        }
    }

    /**
     * 将APP-...,NODE-...后面的服务列表排序后在拼凑在一起返回
     * @param h 头部
     * @param services 服务列表
     * @return 排序后后的服务列表，同时转大写
     */
    static String sortedName(String h, String services) {
        String[] ss = services.toUpperCase().split("[,;]");
        List<String> list = Arrays.asList(ss);
        list.sort(String::compareTo);
        return (h.toUpperCase() + '-' + String.join(",", list));
    }
    
    public static synchronized void register(AbsTokenChecker tokenChecker) {
        String name = tokenChecker.name.toUpperCase();
        if(BUILTIN_CHECKERS.containsKey(name)) {
            LOG.info("token-checker {} already exists", tokenChecker.name);
            return;
        }
        BUILTIN_CHECKERS.put(name, tokenChecker);
    }
}
