package cn.net.zhijian.mesh.frm.service.company;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.util.StringUtil;

/**
 * 用在私有云中，兼容公有云的/company/api/service/list
 * 此类的实现是不管传入参数是什么，全部返回
 * boot包中的类不涉及反射，所以不需要解决proguard问题，无需在AbstractProcessor中注册
 * `/company/api/service/list`
 * @author flyinmind of csdn.net
 *
 */
public class Services extends AbsProcessor {
    private final IServiceServer server;

    public Services(IServiceServer server, ServiceInfo serviceInfo, ApiInfo apiInfo) {
        super(serviceInfo, apiInfo, "get_services");
        this.server = server;
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        List<Object> services = new ArrayList<>();
        int offset = req.getInt("offset", 0);
        int num = req.getInt("num", 15);
        String search = req.getString("search");
        Collection<ServiceInfo> ss = this.server.services().values();

        if(StringUtil.isEmpty(search)) {
            int i = 0;
            int end = offset + num - 1;
            for(ServiceInfo si : ss) {
                i++;
                if(i < offset || i > end) {
                    continue;
                }
    
                List<Object> one = List.of(si.name, si.displayName, si.author,
                        si.type.ordinal(), si.level, si.version);
                services.add(one);
            }
        } else {
            for(ServiceInfo si : ss) {
                if(!si.name.contains(search) && !si.displayName.contains(search)) {
                    continue;
                }
                List<Object> one = List.of(si.name, si.displayName, si.author,
                        si.type.ordinal(), si.level, si.version);
                services.add(one);
            }
        }
        String[] cols = new String[] {"service", "displayName", "author", "type", "level", "version"};
        Map<String, Object> data = new HashMap<>();
        data.put("services", services);
        data.put("cols", cols);
        data.put("total", ss.size());
        return futureResult(data);
    }
}

