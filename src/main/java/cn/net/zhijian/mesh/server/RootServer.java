package cn.net.zhijian.mesh.server;

import cn.net.zhijian.mesh.MeshException;

/**
 * 1）加载所有service的配置，并更新注册表
 * 2）初始化或升级数据库
 * 安卓版本，在files目录下的目录结构
 * MESH_ROOT-/
 *  mesh_meta.db
 *  services
 *    |_serviceName
 *       |__bin(class, so...)
 *       |__api(API configures)
 *       |__file(static files, for example, templates)
 *           |__放在此目录的，不用认证即可访问，访问的URL无需带/file前缀
 *       |__database.cfg(database/tables define)
 *       |__service.cfg
 *    webdb 服务的数据库都存在webdb服务中 
 *       |__serviceName
 *         |__dbname1
 *         |__dbname2
 * @author flyinmind of csdn.net
 */
public class RootServer extends ServiceServer  {
    /**
     * 加载所有服务的配置信息，并初始化omagent的接口
     * @param workDir 项目运行的根目录
     *  如果有，则用omPwd生成token，从bios主实例获取
     */
    public RootServer(String workDir) throws MeshException {
        super(workDir);
    }
}