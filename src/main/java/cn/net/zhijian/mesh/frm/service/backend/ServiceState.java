package cn.net.zhijian.mesh.frm.service.backend;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.RequestStat;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.StringUtil;

/**
 * 服务状态报告
 * @author flyinmind of csdn.net
 *
 */
final class ServiceState extends AbsProcessor {
    private final IServiceServer vServer;

    public ServiceState(IServiceServer server, ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
        this.vServer = server;
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        int cid = req.cid();
        List<Object[]> rows = new ArrayList<>();
        String services = req.getString("services");
        Set<String> ss;
        Map<String, ServiceInfo> sl = vServer.services();
  
        if(!StringUtil.isEmpty(services)) {
            ss = new HashSet<>(Arrays.asList(services.split(",")));
        } else {
            ss = vServer.services().keySet(); //不传，则返回所有的
        }

        String[] cols = new String[] {"name", "queries", "cpuTime", "hasDb", "ver"};
        for(String s : ss) {
            ServiceInfo si = sl.get(s);
            RequestStat rs = si.getStat(cid);
            long calledTimes = rs == null ? 0 : rs.incApis(0);
            rows.add(new Object[] {si.name, calledTimes, si.cpuTime, si.hasDb, si.version()});
        }

        Map<String, Object> data = MapBuilder.of("list", rows, "cols", cols);
        return futureResult(data);
    }
}