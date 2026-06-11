package cn.net.zhijian.mesh.builtin.webdb;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker.AbsRDBWBuilder;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 解析webdb中的配置文件
 * webdb服务启动时，会定时上报dbs中的配置，告知bios本节点存在，负责哪些分片的操作
 * slaves用于告知webdb在执行写入时，同时要将数据同步到哪些节点。
 * 如果是mysql等本身聚备同步能力的大型数据库，webdb的同步能力不是必须的
 *[
 *    //实例中支持哪些类型的数据库实例，当前type只支持SQLITE
 *    //每个实例上可以有多个db，每个db可以有多个分片(逻辑实例)，方便以后扩展成多个物理实例。
 *    //androidserver中只支持一个db，且包括全部分片。
 *    {
 *        //客户端将level最小的实例作为master，其他作为slave，切换slave时也是level越小越优先
 *        "level":0,
 *        "no":0, //相同分区中，db.no要唯一，可以用于区分服务、公司等
 *        "shardStart":0, //从0开始，包括0，用在AbsRDBWorker.isValidSharding()中
 *        "shardEnd":32768, //到32768结束，不包括32768
 *        "type":"SQLITE",
 *        //从实例，地址+端口，为空的情况，则只有当前实例
 *        //可以有多个，每个服务器中的dbs配置应该一致
 *        //slaves只用于在数据库实例加载时，让它知道启动哪些消费队列
 *        //在客户端选择level最小的作为主节点，其他都是从节点
 *        "slaves":[]
 *    }
 *]
 *<p></p>
 * 一个master对应一组slave，每个slave上承载的分片最好与master保持一致；
 * 但是多个master同步到同一个slave是没有问题的，这时slave上要包括这些master的分片。
 * 这种情况，一旦发生切换，slave需要承担原来多个master的压力。
 *<p></p>
 *         master0(0-16384) -----------|
 * |------ master1(16384-32768)        |
 * |       slave00(0-16384) <----------|
 * |-----> slave01(16384-32768)        |
 * |       slave10(0-16384) <----------|
 * |-----> slave11(16384-32768)        |
 * |------>slave20(0-32768) <----------|
 *<p></p>
 * 在上面的例子中，master0.dbs的所有db分片都必须是0-16384，slaves为[slave00,slave10,slave20]，
 * 相应的，master1.dbs的所有db分片都必须是16384-32768，slaves为[slave01,slave11,slave20].
 * master与slave中的dbs.no需要相同，否则同步时会被拒绝。
 * 在执行同步复制时，只看dbno，不看分片、类型。
 * 这样即可以支持多个分片的合并，比如上例中slave20接受了所有分片；
 * 也可以支持不同种类数据库的同步，前提是sql必须能够兼容，所以实现时尽量使用简单通用的sql语法。
 * 在slave实例的配置中，slaves不为空的情况，可以实现切换后的反向同步。
 * webdb.sync接口在处理同步过来的数据时，不会让这些数据进入同步队列。
 *
 */
final class RunConfig {
    private static final Logger LOG = LogUtil.getInstance();

    /*
     * 一个节点可以存放不同服务的数据库，甚至不同分片的数据库也可以共存。
     * webdb上报状态时，只会按配置中的内容上报，而不会逐个db实例上报。
     * 比如配置的是{"service":"*","level":0,"sharding":"xxx"}
     * 上报状态时，service就是*，而不是将所有在其中的db都上报一次。
     * 相同service、sharding的实例，优先使用level较小的实例，
     * 这一点可以用在主备切换中。
     */
    public final AbsDB[] dbs;

    private RunConfig(AbsDB[] dbs) {
        this.dbs = dbs;
    }
    
    public static RunConfig createDefault() {
        return new RunConfig(new AbsDB[]{AbsDB.defaultDb()});
    }

    public static RunConfig parse(File cfgFile) {
        List<Object> dbList = JsonUtil.jsonFileToList(cfgFile, true);
        if(dbList == null || dbList.isEmpty()) {
            LOG.error("Invalid run config file `{}`", cfgFile);
            return null;
        }

        return parse(dbList);
    }

    public static File configFile(String serviceHomeDir) {
        return new File(FileUtil.addPath(serviceHomeDir, "run.cfg"));
    }

    public boolean needBackup() {
        for(AbsDB db : dbs) {
            if(db.needBackup()) {
                return true;
            }
        }
        return false;
    }
    /**
     * @param dbList 配置内容
     * @return 配置对象
     */
    static RunConfig parse(List<Object> dbList) {
        AbsDB[] dbs = new AbsDB[dbList.size()];
        for(int i = 0; i < dbList.size(); i++) {
            Map<String, Object> map = ValParser.parseObject(dbList.get(i));

            dbs[i] = AbsDB.parse(map);
            if(dbs[i] == null) {
                LOG.error("Fail to parse db@{} in webdb run.cfg", i);
                return null;
            }
        }

        return new RunConfig(dbs);
    }

    int maxLevel() {
        int level = -1;
        for(AbsDB db : dbs) {
            if(db.level > level) {
                level = db.level;
            }
        }
        return level;
    }
    
    /**
     * 将db列表转成map，防止proguard改变了字段名称，导致作为请求参数时出现错误。
     * 此处不必上报type字段
     * @return maps
     */
    List<Map<String, Object>> dbsList() {
        List<Map<String, Object>> dbMaps = new ArrayList<>();
        for(AbsDB db : dbs) {
            dbMaps.add(db.toMap());
        }
        return dbMaps;
    }
    
    /**
     * 获取数据库builder
     * @param dbNo 数据库编号，用于确定为哪些公司服务
     * @param si 发起调用的服务
     * @param cid 公司id
     * @param service 服务名称，与cid共同决定数据库存放的路径
     * @param db 数据库名称
     * @return 数据库创建器
     */
    AbsRDBWBuilder getBuilder(int dbNo, ServiceInfo si, int cid, String service, String db) {
        for(AbsDB d : dbs) {
            if(d.no == dbNo) {
                return d.getBuilder(si, cid, service, db);
            }
        }
        LOG.error("Fail to create DWBuilder for {}.{},not in this node", service, db);
        return null;
    }    
}
