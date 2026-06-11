package cn.net.zhijian.mesh.frm.process;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 静态JSON内容
 * 在其中可以添加占位符，将参数或前面处理的结果格式化输出到一个json中。
 * 需要返回一些固定的静态配置时，可以使用，比如服务的角色定义，
 * 也可以定义为字符串，在其中做一些简单的运算，比如用CALCULATE、CONDITION对参数进行运算。
 * 另外，在系统启动时，会从服务api目录下读取所有".json"文件，生成静态接口，
 * 调用构造函数StaticProcessor(ServiceInfo,ApiInfo,String,Map<String,Object>)
 * @author flyinmind of csdn.net
 *
 */
public class StaticProcessor extends AbsProcessor {
    private static final String CFG_DATA = "data";
    private static final Logger LOG = LogUtil.getInstance();
    private Map<String, Object> content = null;
    private ScriptElement[] elements = null;

    //用于反射方式创建对象，不可删除
    public StaticProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    public StaticProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName, Map<String, Object> data) {
        super(serviceInfo, apiInfo, processName);
        this.content = data;
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        if(content != null) {
            return futureResult(content);
        }
        
        String json = translateElements(this.elements, req, resp);
        if(json == null) {
            LOG.error("Invalid json in `{}`, fail to compile with req", this.name());
            return futureResult(RetCode.INTERNAL_ERROR, "fail to parse elements");
        }
        Map<String, Object> data = JsonUtil.jsonToMap(json);
        if(data != null) {
            return futureResult(data);
        }
        return futureResult(RetCode.INTERNAL_ERROR, "invalid json");
    }

    @Override
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        Object o = cfg.get(CFG_DATA);
        if(o instanceof String) {
            String content = (String)o;
            this.elements = ScriptElement.parsePlaceHolder(content, availableParas(request), "\"", "\\\"");
            if(this.elements == null || this.elements.length == 0) {
                LOG.error("Fail to parse {} in `{}`", content, url);
                return false;//有内容返回时，才有意义，否则接口没有存在的必要
            }
        } else {
            this.content = ValParser.parseObject(o);
            if(this.content == null || this.content.isEmpty()) {
                LOG.error("Fail to parse json object {} in `{}`", o, url);
                return false;
            }
        }
        return super.parse(url, cfg, request);
    }
}