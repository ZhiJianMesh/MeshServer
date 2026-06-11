package cn.net.zhijian.mesh.frm.service.httpdns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;

class RouteConfig {
    //每行有两个string，第一个是服务名，第二个是地址
    //如此设置，只是为了方便返回给客户端
    public final List<String[]> lanDns;
    public final List<String[]> wanDns;
    public final Map<String, Integer> lookupMap; //用于lookup时用服务名查找

    public RouteConfig(List<String[]> lanDns, List<String[]> wanDns, Map<String, Integer> lookupMap) {
        this.lanDns = lanDns;
        this.wanDns = wanDns;
        this.lookupMap = lookupMap;
    }
    
    /**
     * 默认route配置，所有服务地址都使用相同的内、外地址，此地址从公司配置中获取
     * 单例时可以这样使用，集群时
     * @param company 公司信息
     * @param server 服务
     * @return 默认route配置
     */
    public static RouteConfig defaultRoute(CompanyInfo company, IServiceServer server) {
        String globalWan = company.outsideAddr();
        String globalLan = company.insideAddr;
        int idx = 0;
        List<String[]> lanDns = new ArrayList<>(); //每行有两个string，第一个是服务名，第二个是地址
        List<String[]> wanDns = new ArrayList<>();
        Map<String, Integer> lookupMap = new HashMap<>(); //用于lookup时用服务名查找
        
        //配置文件不存在时，直接使用company中的配置，company配置是登录时从至简网格获取的
        //至简网格中的内外网地址是在公司注册时上报的
        //对于单例的情况，内外网地址都只有一个
        for(ServiceInfo si : server.services().values()) {
            lanDns.add(new String[]{si.name, globalLan});
            wanDns.add(new String[]{si.name, globalWan});
            lookupMap.put(si.name, idx);
            idx++;
        }
        
        if(!lookupMap.containsKey(IConst.SERVICE_COMPANY)) {
            //用于查询私网部署的公司服务，提供一些兼容性接口
            //实现类在包cn.net.zhijian.mesh.frm.service.vCloud中
            //比如/company/api/token、/company/api/logo等
            lanDns.add(new String[]{IConst.SERVICE_COMPANY, globalLan});
            wanDns.add(new String[]{IConst.SERVICE_COMPANY, globalWan});
            lookupMap.put(IConst.SERVICE_COMPANY, idx);
        }
        
        return new RouteConfig(lanDns, globalWan == null ? null : wanDns, lookupMap);
    }

    public String[] lookup(String service, boolean wan) {
        Integer idx = lookupMap.get(service);
        if(idx != null) { //服务存在于本地
            if(wan || PartitionConfig.instance().isInCloud) {
                return wanDns != null ? new String[] {wanDns.get(idx)[1]} : null;
            } else {
                return new String[] {lanDns.get(idx)[1]};
            }
        }
        return null;
    }

    public List<String[]> probe(boolean wan) {
        if(wan || PartitionConfig.instance().isInCloud) {
            return wanDns != null ? Collections.unmodifiableList(wanDns) : null;
        }
        return Collections.unmodifiableList(lanDns);
    }
}
