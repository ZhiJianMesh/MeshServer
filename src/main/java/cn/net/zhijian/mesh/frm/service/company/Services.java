package cn.net.zhijian.mesh.frm.service.company;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo.ServiceType;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 用在私有云中，兼容公有云的/company/api/service/list
 * 此类的实现是不管传入参数是什么，全部返回
 * boot包中的类不涉及反射，所以不需要解决proguard问题，无需在AbstractProcessor中注册
 * `/company/api/service/list`
 * @author flyinmind of csdn.net
 *
 */
public class Services extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    
    public Services(ServiceInfo serviceInfo, ApiInfo apiInfo) {
        super(serviceInfo, apiInfo, "get_services");
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        ServiceInfo si = serviceInfo();
        String url = "/service/getList";
        ServiceReqBuilder reqServices = new ServiceReqBuilder(si, SERVICE_BIOS)
            .url(url)
            .cid(req.cid())
            .appToken("*")
            .traceId(req.traceId);

        return BiosClient.get(reqServices).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK || hr.data == null) {
                LOG.error("Fail to call {}, code:{}", url, hr.code);
                return CompletableFuture.completedFuture(hr);
            }
            List<List<Object>> services = new ArrayList<>();
            String search = req.getString("search");
            search = StringUtil.isEmpty(search) ? search : null;
            Map<String, Object> sl = ValParser.getAsObject(hr.data, "services");
            for(Map.Entry<String, Object> e : sl.entrySet()) {
                addList(e.getKey(), ValParser.parseObject(e.getValue()), services);
            }
            List<List<Object>> respList;
            if(search == null) {
                int offset = req.getInt("offset", 0);
                int num = req.getInt("num", 15);
                int end = offset + num - 1;
                respList = services.subList(offset, end);
            } else {
                respList = new ArrayList<>();
                for(List<Object> e : services) {
                    String name = ValParser.parseString(e.get(0));
                    String dispName = ValParser.parseString(e.get(1));
                    if(dispName.contains(search) || name.contains(search)) {
                        respList.add(e);
                    }
                }
            }
            String[] cols = new String[] {"service", "displayName", "author", "type", "level", "version"};
            Map<String, Object> data = new HashMap<>();
            data.put("services", respList);
            data.put("cols", cols);
            data.put("total", services.size());
            return futureResult(data);
        }, Pool);
    }
    
    private static void addList(String name, Map<String, Object> si, List<List<Object>> services) {
        if(si == null || si.isEmpty()) {
            return;
        }
        boolean visible = ValParser.getAsBool(si, "visible", false);
        if(!visible) {
            return;
        }
        String type = ValParser.getAsStr(si, "type").toUpperCase();
        if(!ServiceType.COMPANY.name().equals(type) && !ServiceType.COMMON.name().equals(type)) {
            return;
        }
        String dispName = ValParser.getAsStr(si, "dispName");
        String author = ValParser.getAsStr(si, "author");
        int version = StringUtil.verToInt(ValParser.getAsStr(si, "version", "0.1.0"));
        int level = ValParser.getAsInt(si, "level", 4);
        List<Object> one = List.of(name, dispName, author, type, level, version);
        services.add(one);
    }
}

