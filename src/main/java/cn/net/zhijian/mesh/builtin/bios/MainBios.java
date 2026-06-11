package cn.net.zhijian.mesh.builtin.bios;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.dbworker.ItemInfo;
import cn.net.zhijian.mesh.dbworker.TreeDBWorker;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;

/**
 * 查询或设置主bios编号
 * @author flyinmind of csdn.net
 *
 */
class MainBios extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    
    static final String ITEM_MAINBIOS = "/common/mainbios";
    private static TreeDBWorker meta;

    public MainBios(ServiceInfo si, ApiInfo apiInfo, String name) {
        super(si, apiInfo, name);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        PartitionConfig pc = PartitionConfig.instance();
        String action = req.getString("act").toLowerCase();
        if(action.equals("get")) {
            String main = meta.getValue(ITEM_MAINBIOS);
            return HandleResult.future(MapBuilder.of("main", main, "list", pc.biosDNS().toList()));
        }
        
        //调用当前实例的mainbios.set，意味着该实例已被其他服务选作主bios
        String old = req.getString("old");
        String next = req.getString("next");
        //准备选为主BIOS的地址与本地地址不一致，则直接返回错误
        if(!next.equals(ChannelConfig.instance().localHttpAddr())) {
            return HandleResult.future(RetCode.FORBIDDEN, "not appointed main bios");
        }

        ItemInfo item = meta.putAtomic(ITEM_MAINBIOS, next, old, System.currentTimeMillis());
        //bios的partition不能及时更新，所以在有请求时更新一下
        pc.biosDNS().setMain(item.val);
        if(!next.equals(item.val)) { //没有改变，没必要通知
            notifyOthers(next); //及时同步给其他bios，防止数据库同步慢了
        }

        Map<String, Object> data = MapBuilder.of("main", item.val, "list", pc.biosDNS().toList());

        return HandleResult.future(data);
    }
    
    private void notifyOthers(String newMain) {
        PartitionConfig pc = PartitionConfig.instance();
        NodeAddress[] slaves = pc.biosDNS().slaves(ChannelConfig.instance().localHttpAddr());
        if(slaves == null || slaves.length == 0) {
            return;
        }
        ServiceInfo caller = serviceInfo();
        String token = BiosClient.appToken(caller, caller.name, "*");
        int cid = CompanyInfo.instance().id;

        for(NodeAddress slave : slaves) {
            ServiceReqBuilder req = new ServiceReqBuilder(caller, caller.name)
                    .url("/mainchanged").appendPara("main", newMain)
                    .token(token)
                    .cid(cid)
                    .traceId("mainchged" + slave.addr);

            ServiceClient.servicePut(slave, req).whenCompleteAsync((hr, e) -> {
                if(e != null) {
                    LOG.error("Fail to notify bios@{}", slave.addr, e);
                    return;
                }
                if(hr.code != RetCode.OK) {
                    LOG.error("Fail to notify bios@{}, result:{}", slave.addr, hr.brief());
                }
            });
        }
    }
    
    static void setMetaDb(TreeDBWorker meta) {
        MainBios.meta = meta;
    }
}
