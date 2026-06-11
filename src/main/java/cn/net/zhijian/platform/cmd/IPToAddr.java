package cn.net.zhijian.platform.cmd;

import java.util.concurrent.TimeUnit;

import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.ValParser;

public class IPToAddr extends AbsCommand {
    public IPToAddr(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if(args.length < 1) {
            printHelp(help());
            return false;
        }
        
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_ADDRESS)
                .url("/location?ip=" + args[0]);
        ServiceClient.cloudGet(req).whenCompleteAsync((hr, e) -> {
             if(e != null) {
                 System.out.println("Fail to get location from cloud:" + e.getMessage());
                 return;
             }
             if(hr.code != RetCode.OK) {
                 System.out.println("Fail to get location from cloud, result:" + hr.brief());
                 return;
             }
             System.out.println("Telcom:" + ValParser.getAsStr(hr.data, "telecom"));
             System.out.println("Country:" + ValParser.getAsStr(hr.data, "country"));
             System.out.println("Province:" + ValParser.getAsStr(hr.data, "province"));
             System.out.println("City:" + ValParser.getAsStr(hr.data, "city"));
         }, IThreadPool.Pool).get(10, TimeUnit.SECONDS);
        
         return true;
    }

    @Override
    public String[] help() {
        return new String[]{"convert IPv4 to a physical address",
                name + " ip [path_to_ipdatabase]"};
    }
}
