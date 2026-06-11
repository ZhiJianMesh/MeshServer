package cn.net.zhijian.mesh.frm.service.httpdns;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig.DeployMode;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 公司服务器的内置服务，用于端侧在公司内网发现服务器
 * 公司内外可以是公司内部的服务器集群，也可以是租用的公有云服务器集群
 */
abstract class HttpDnsBase extends AbsProcessor {
    private static final long CHECK_INTERVAL = 5L * 60 * 1000;
    private static final Logger LOG = LogUtil.getInstance();
    
    private static volatile long foreCheckTime = 0;
    private static volatile RouteConfig routeCfg = null;
    protected final IServiceServer server;

    protected abstract CompletableFuture<HandleResult> handle1(AbsServerRequest req, Map<String, Object> respData);
    
    public HttpDnsBase(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName, IServiceServer server) {
        super(serviceInfo, apiInfo, processName);
        this.server = server;
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        //accecc_token传accessCode，与服务端httpdns中的是同一个，但是httpdns中是sha256后的code
        //私网服务器不进行sha256运算，是为了防止攻击者盗取httpdns中的值，接入私网服务器
        int cid = req.cid();
        CompanyInfo localCompany = CompanyInfo.instance();
        
        if(localCompany.id != cid) {//只能对本地公司执行命令
            return futureResult(RetCode.NO_RIGHT, "invalid company id " + cid);
        }
        
        String token = req.header(HEAD_ACCESS_TOKEN);
        if(StringUtil.isEmpty(token) || !token.equals(localCompany.accessCode())) {
            return futureResult(RetCode.INVALID_TOKEN);
        }
        return handle1(req, respData);
    }

    protected CompletableFuture<RouteConfig> getRouteConfig(CompanyInfo ci) {
        long cur = System.currentTimeMillis();
        if(cur - foreCheckTime >= CHECK_INTERVAL) {
            foreCheckTime = cur; //尽量立刻阻止其他线程进入此分支执行查询，非原子变量，不能完全阻止
            
            //单例时无需查询bios就可以获得路由信息，全部服务的地址指向本实例
            if(PartitionConfig.instance().mode == DeployMode.SINGLETON) {
                routeCfg = RouteConfig.defaultRoute(ci, server);
                return CompletableFuture.completedFuture(routeCfg);
            }
            
            return initRouteConfig(ci, serviceInfo());
        }
        return CompletableFuture.completedFuture(routeCfg);
    }

    private static CompletableFuture<RouteConfig> initRouteConfig(CompanyInfo ci, ServiceInfo si) {
        String token = ci.adminToken(SERVICE_BIOS).generate();
        ServiceReqBuilder reqSrvList = ServiceClient.backendReqBuilder(SERVICE_BIOS)
                .url("/service/list")
                .cid(ci.id)
                .token(token)
                .traceId("getservices");

        //发起异步调用刷新路由配置，但是并不会等待它的结果
        return BiosClient.get(reqSrvList).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to get service list,result:{}", hr.brief());
                return CompletableFuture.completedFuture(null);
            }
            Map<String, Object> list = ValParser.getAsObject(hr.data, "services");
            if(list == null || list.isEmpty()) {
                LOG.warn("Invalid services list,use default route config");
                return CompletableFuture.completedFuture(null);
            }

            Map<String, Boolean> services = new HashMap<>();
            list.forEach((k, v) -> {
                Map<String, Object> service = ValParser.parseObject(v);
                services.put(k, ValParser.getAsBool(service, "visible"));
            });
            return requestRoute(ci, token, services);
        }, Pool);
    }
    
    private static CompletableFuture<RouteConfig> requestRoute(CompanyInfo ci, String token, Map<String, Boolean> services) {
        ServiceReqBuilder reqAddrs = ServiceClient.backendReqBuilder(SERVICE_BIOS)
                .url("/status/srvlist")
                .cid(ci.id)
                .token(token)
                .traceId("getsrvlist");
        return BiosClient.get(reqAddrs).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to get srvlist list,result:{}", hr.brief());
                return null;
            }

            List<Object> l = ValParser.getAsList(hr.data, "list");
            if(l == null || l.isEmpty()) {
                LOG.warn("Invalid services list,use default route config");
                return null;
            }
            
            String outsideAddr = ci.outsideAddr(); //可能有多个
            Map<String, String> lanMap = new HashMap<>();
            for(Object o : l) {
                Map<String, Object> one = ValParser.parseObject(o);
                String name = ValParser.getAsStr(one, "service");
                Boolean visible = services.get(name);
                if(visible != null && visible) {
                    String addr = ValParser.getAsStr(one, "addr");
                    lanMap.merge(name, addr, (oldVal, newVal)-> oldVal + ',' + newVal);
                }
            }
            List<String[]> lans = new ArrayList<>();
            List<String[]> wans = new ArrayList<>();
            Map<String, Integer> map = new HashMap<>();
            int idx = 0;
            for(Map.Entry<String, String> e : lanMap.entrySet()) {
                String service = e.getKey();
                lans.add(new String[] {service, e.getValue()});
                wans.add(new String[] {service, outsideAddr});
                map.put(service, idx++);
            }
            
            routeCfg = new RouteConfig(lans, wans, map);
            return routeCfg;
        }, Pool);        
    }
}
