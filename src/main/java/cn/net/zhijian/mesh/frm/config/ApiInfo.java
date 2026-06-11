package cn.net.zhijian.mesh.frm.config;

import java.util.HashSet;
import java.util.Set;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
/**
 * API的基本信息
 * 因为需要向IProcessor传递，ApiMethod不便于整体传递，所以将这部分信息独立出来，
 * @author flyinmind of csdn.net
 *
 */
public final class ApiInfo {
    /**
     * 如果指定为PRIVATE，则调用时会判断token，需要指定tokenChecker，
     * 如果是PUBLIC，则无需传access_token的头
     */
    public static final int FLAG_PRIVATE = 0x0001;

    /**
     * 接口的分类，
     * 就是接口的配置文件名称，用于鉴权
     */
    public final String cls;

    /**
     * 接口url，完整路径是'/服务名/api/配置文件名/接口名称name'，
     * 如果配置文件名为root.cfg，则为'/服务名/api/接口名称name'
     */
    public final String url;

    /**
     * 只支持get、post，不指定则两种请求都可以
     */
    public final String method;

    /**
     * 指定一个接口属于哪一个特性。
     * 接口的配置文件名就是类型，feature指定接口在这个类型中的特性，
     * 此参数用于权限管理中，用于判断角色或调用方服务是否具有该feature的调用权限，
     * 如果不设置，则为null，表示不限制
     */
    public final String feature;

    //public|private...可能有多种属性，所以使用flag
    private final int flag;

    /**
     * 每个处理中设置的额外参数，
     * 用于解决在java processor中产生，但是参数中未定义的中间参数
     */
    private Set<String> extParas = null;
    
    public ApiInfo(String cls, String method, UrlPathInfo url, String feature, boolean isPrivate) {
        this.cls = cls;
        this.method = StringUtil.isEmpty(method) ? null : method.toUpperCase();
        this.feature = StringUtil.isEmpty(feature) ? null : feature.toLowerCase();
        this.url = url.toString().toLowerCase();
        this.flag = isPrivate ? FLAG_PRIVATE : 0;
    }

    public ApiInfo(String cls, String method, UrlPathInfo url, String feature) {
        this(cls, method, url, feature, true);
    }
    
    /**
     * 可以使用的系统参数
     * 只在解析时调用，所以没有缓存，每次都重新生成
     * @return 系统参数列表
     */
    public Set<String> availableSysParas() {
        Set<String> paras = new HashSet<>();
        paras.add(IConst.EMBEDED_REQUESTAT);
        paras.add(IConst.EMBEDED_SERVICE);
        paras.add(IConst.EMBEDED_CODE);
        paras.add(IConst.EMBEDED_INFO);
        paras.add(IConst.EMBEDED_ERROR_HANDLE);

        if(extParas != null) {
            paras.addAll(extParas);
        }
        return paras;
    }
    
    /**
     * 添加一个附加的参数，用于解决java代码中产生的中间参数无法在配置中解析的问题
     * @param para 参数名称
     */
    public synchronized void addExtPara(String para) {
        if(extParas == null) {
            extParas = new HashSet<>();
        }
        extParas.add(para);
    }
    
    public boolean isFlagSet(int flg) {
        return (this.flag & flg) == flg; //不判断是否等于0，防止fla有多个bit位
    }
    
    @Override
    public String toString() {
        return "{\"method\":" + method + "\",\"url\":\""
               + url + "\",\"flag\":" + flag + "}";
    }
}