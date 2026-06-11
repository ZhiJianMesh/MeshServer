package cn.net.zhijian.mesh.builtin.webdb;

import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IDBConst;

abstract class DBHandlerBase extends AbsProcessor implements IDBConst {
    protected static RunConfig runCfg;

    public DBHandlerBase(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    public static void setRunConfig(RunConfig runCfg) {
        DBHandlerBase.runCfg = runCfg;
    }
}