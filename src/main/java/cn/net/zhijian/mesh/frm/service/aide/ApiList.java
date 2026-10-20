package cn.net.zhijian.mesh.frm.service.aide;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ResponseInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.mesh.frm.method.ApiMethod;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.ValParser;

/**
 * 在设置RBAC时，需要了解服务中所有接口的信息
 * @author flyinmind of csdn.net
 *
 */
public class ApiList extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    
    public ApiList(ServiceInfo si, ApiInfo apiInfo) {
        super(si, apiInfo, API_SERVICE_APILIST);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        List<Object> apis = new ArrayList<>();
        ServiceInfo si = req.serviceInfo();
        try {
            File apiDir = new File(FileUtil.addPath(si.homeDir, SERVICE_URL_API));
            if(!apiDir.isDirectory()) {
                return HandleResult.future();
            }
            File[] cfgFiles = apiDir.listFiles((d, name) -> name.endsWith(".cfg"));
            if(cfgFiles == null) {
                return HandleResult.future();
            }
            Object e;
            Map<String, Object> desc;
            Map<String, Object> api;
            String name;
            List<Object> defs;
            String cls;
            String fileName;
            String property;
            String tokenChecker;
            boolean onlyUser = !ValParser.parseBool(req.get("showall"), false);
            
            for(File f : cfgFiles) {
                fileName = f.getName();
                cls = fileName.substring(0, fileName.length() - 4); //去掉.cfg
                defs = JsonUtil.jsonFileToList(f, true);
                if(defs == null) {
                    continue;
                }
                for(Object o : defs) {
                    api = ValParser.parseObject(o);
                    if(api == null) {
                        continue;
                    }
                    property = ValParser.getAsStr(api, ApiMethod.CFG_PROPERTY).toUpperCase();
                    if(onlyUser && !ApiMethod.PROPERTY_PUBLIC.equals(property)) {
                        tokenChecker = ValParser.getAsStr(api, ApiMethod.CFG_TOKENCHECKER).trim().toUpperCase();
                        if(!tokenChecker.startsWith(USER_TOKEN_CHECKER)) {
                            continue;
                        }
                    }
                    desc = new HashMap<>();
                    name = ValParser.getAsStr(api, ApiMethod.CFG_NAME);
                    if(name.startsWith(IServiceServer.INIT_METHOD_HEAD)) {
                        continue; //初始化接口，不必记录
                    }

                    desc.put(ApiMethod.CFG_METHOD, api.get(ApiMethod.CFG_METHOD));
                    desc.put(ApiMethod.CFG_PROPERTY, property);
                    e = api.get("comment");
                    if(e != null) {
                        desc.put("comment", e);
                    }
                    e = api.get(RequestInfo.PROPERTY_REQUEST);
                    if(e != null) {
                        desc.put(RequestInfo.PROPERTY_REQUEST, e);
                    }
                    e = api.get(ResponseInfo.PROPERTY_RESPONSE);
                    if(e != null) {
                        desc.put(ResponseInfo.PROPERTY_RESPONSE, e);
                    }
                    //完整url：/service/api/cls/name；root.cfg时cls为空
                    String url;
                    if(!SERVICE_URL_ROOT.equals(cls)) {
                        url = "/" + si.name + "/api/" + cls + "/" + api.get(ApiMethod.CFG_NAME);
                    } else {
                        url = "/" + si.name + "/api/" + api.get(ApiMethod.CFG_NAME);
                    }
                    desc.put("url", url);
                    apis.add(desc);
                }
            }
        } catch(Exception e) {
            LOG.error("Fail to describe services", e);
        }
        Map<String, Object> data = MapBuilder.of("count", apis.size(), "apis", apis);
        return HandleResult.future(data);
    }
}