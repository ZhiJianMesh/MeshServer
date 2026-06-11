package cn.net.zhijian.platform.cmd;

import java.util.concurrent.TimeUnit;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.server.ServiceTool;
import cn.net.zhijian.platform.IPlatformConst;

public class Service extends AbsCommand {
    public Service(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if(args.length < 2) {
            printHelp(help());
            return false;
        }
        
        //先本地加载信息，如果没有登录过，则不能设置accesscode
        CompanyInfo localCompany = CompanyInfo.instance();
        if(localCompany == null || !localCompany.isValid()) {
            System.out.println("failed,haven't logined,use login command to do it");
            return false;
        }
        
        String cmd = args[0];
        String service = args[1];
        if(cmd.equalsIgnoreCase("install")) {
            return install(service);
        }
        
        if(cmd.equalsIgnoreCase("uninstall")) {
            if(args.length < 3) {
                printHelp(help());
                return false;
            }
            return uninstall(service, args[2]);
        }
        
        if(cmd.equalsIgnoreCase("update")) {
            if(args.length < 3) {
                printHelp(help());
                return false;
            }
            return update(service, args[2]);
        }

        printHelp(help());
        return false;
    }

    @Override
    public String[] help() {
        return new String[]{name + " install/uninstall/update",
            "only download/uppackage or remove service files, need restart server",
            "1)intall service_name",
            "2)unintall service_name omPwd",
            "3)update service_name omPwd - uninstall then install",
        };
    }
    
    /**
     * 安装服务
     * 1）查询服务本身信息，及所有依赖服务的信息；
     * 2）从资源网站逐个下载安装包，并解压；
     * 3）如果系统处于运行状态，则重新加载所有服务。
     * @param service 服务名称
     * @return 执行结果
     * @throws Exception 
     */
    private boolean install(String service) throws Exception {
        String evm = PartitionConfig.instance().environment;
        HandleResult result = ServiceTool.install(service, evm).get(60, TimeUnit.SECONDS);
        System.out.println("Install " + service + ",evm:" + evm + ",result:" + result.brief());
        return result.code == RetCode.OK;
    }

    private boolean uninstall(String service, String omPwd) throws Exception {
        HandleResult result = ServiceTool.unInstall(service, omPwd).get(30, TimeUnit.SECONDS);
        System.out.println("UnInstall " + service + ",result:" + result.brief());
        return result.code == RetCode.OK;
    }
    
    /**
     * 升级服务，先卸载(不会删除数据库)，再安装，安装后要重启，只需数据库升级脚本
     * @param service 服务名
     * @throws Exception
     */
    private boolean update(String service, String omPwd) throws Exception {
        HandleResult result = ServiceTool.update(service, omPwd, IPlatformConst.EVM)
                .get(30, TimeUnit.SECONDS);
        System.out.println("Update " + service + " result:" + result.brief());
        return result.code == RetCode.OK;
    }
}
