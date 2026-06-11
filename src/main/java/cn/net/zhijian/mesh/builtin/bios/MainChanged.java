package cn.net.zhijian.mesh.builtin.bios;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.dbworker.TreeDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;

/**
 * 当前主bios实例通知其他bios主已变更
 * @author flyinmind of csdn.net
 *
 */
class MainChanged extends AbsProcessor {
    private static TreeDBWorker meta;
    
    public MainChanged(ServiceInfo si, ApiInfo apiInfo, String name) {
        super(si, apiInfo, name);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String main = req.getString("main");
        //当前实例改变了main，如果有服务调用了错误的bios设置主bios，也会失败
        //因为设置时使用的时putAtomic函数
        meta.put(MainBios.ITEM_MAINBIOS, main, System.currentTimeMillis());

        PartitionConfig pc = PartitionConfig.instance();
        pc.biosDNS().setMain(main);

        return HandleResult.future();
    }
    
    static void setMetaDb(TreeDBWorker meta) {
        MainChanged.meta = meta;
    }
}
