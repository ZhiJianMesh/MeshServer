package cn.net.zhijian.mesh.bean;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.frm.abs.AbstractDNS;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 1）只有一个数据库实例的情况，不分库；
 * 2）如果有多个实例，数据会被分成32768片，每个实例上承载不同的分片段；
 * 3）默认是均匀分布的，也可以在OM上设置，如果设置，一定要保证32768片全部都分配到不同实例上了；
 * 4）如果增加或减少实例，所有使用该数据库的客户端必须重启，手动调整数据分布，然后才能重启服务，更新分片的分布信息；
 * 5）因为表设计问题，treedb只能按照路径分库，不能按完整的‘路径+key’分库；
 * 6）如果db有相同region从库与跨region备库，从库的分片与主库一致，但是level大于0，
 * 多个从库的情况，会依level从小到大排列，备库总是放在最后面，即使主从库都发生故障，也不会切换到它们。
 * <p></p>
 * 重要提示：
 * dbNo确定一组数据库，sharding将这组数据再次细分。
 * 在私网环境dbNo在bios.service中指定，至简网格中在company指定。
 * 数据库实例需要设置好同步关系，逻辑上是按sharding进行同步的，从库中需要包括主库的sharding，
 * 因为记录数据库实例的主键是(partId,dbNo,shardStart,addr)。
 * 服务层判断主库故障会切换从库，但是不处理这种同步关系。
 * 相同dbNo、sharding，客户端将level最小的节点当作主节点。
 * 
 * @author flyinmind of csdn.net
 *
 */
public final class WebdbDNS extends AbstractDNS {
    private static final Logger LOG = LogUtil.getInstance();

    public final String service;
    public final String db;
    public final ShardingNode[] shardings;

    /**
     * @param service 服务名称
     * @param db 数据库名称
     * @param shardings 节点
     * @param expiresIn 多少秒之后过期，<=0，表示永不过期
     */
    public WebdbDNS(String service, String db, ShardingNode[] shardings, int expiresIn) {
        super(expiresIn);
        this.shardings = shardings;
        this.service = service;
        this.db = db;
    }

    /**
     * 一次解析一个dbno的所有sharding节点，
     * 会有重复的sharing分片，这时将排在后面的sharding作为前面重复sharding的从节点
     * @param service 服务名
     * @param db 数据库名
     * @param shardList 分片配置，从bios.dbstatus中查询的列表，是每个webdb定时上报的数据库分段信息
     * @return 包含一个dbno的所有sharding解析器
     */
    public static WebdbDNS parse(String service, String db, List<Object> shardList) {
        int num = shardList.size();
        if(num == 0) {
            LOG.error("Invalid web dns config, no shardings in {}.{}", service, db);
            return null;
        }
        List<ShardingNode> shardings = new ArrayList<>();
        List<ShardingNode> all = new ArrayList<>();

        //每个bit位代表一个shard是否被覆盖到
        int[] shardingBits = new int[IDBConst.MAX_SHARDING_NUM / Integer.SIZE];
        Arrays.fill(shardingBits, 0);

        //在查询shardList时已经按照dbNo,shardStart,level升序排列
        //所以level越小越靠前，最小的作为master，其他作为slave
        for(Object o : shardList) {
            Map<String, Object> map = ValParser.parseObject(o);
            ShardingNode sharding = ShardingNode.parse(service, db, map);
            if(sharding == null) {
                LOG.error("Fail to parse node:{} in {}.{}", map, service, db);
                return null;
            }
            all.add(sharding);

            if(!IDBConst.DB_MASTER.equals(sharding.mode)) {
                 continue;
            }
            //单纯的slave实例不用加到shardings中，因为master中已经记录了slaves，读的时候可能会使用。
            //不是slave的情况，需要检查分片是否有重叠
            for(int j = sharding.start; j < sharding.end; j++) {
                int byteN = j / Integer.SIZE;
                int bitN = 1 << (j % Integer.SIZE);
                if((shardingBits[byteN] & bitN) != 0) {
                    LOG.error("Sharding {} is overlaped in {}.{}", j, service, db);
                    return null;
                }
                shardingBits[byteN] |= bitN;
            }
            shardings.add(sharding);
        }
        
        //检查分片是否有缺失
        for(int j = 0; j < IDBConst.MAX_SHARDING_NUM; j++) {
            int byteN = j / Integer.SIZE;
            int bitN = 1 << (j % Integer.SIZE);

            if((shardingBits[byteN] & bitN) == 0) {
                LOG.error("Sharding {} is not covered in {}.{}", j, service, db);
                return null;
            }
        }
        
        //删除不存在的从实例
        for(ShardingNode sn : shardings) {
            sn.validateSlaves(all);
        }

        //每5分钟更新一次，更新失败时，沿用老的
        return new WebdbDNS(service, db, shardings.toArray(new ShardingNode[]{}), DEFAULT_EXPIRES_IN);
    }

    /**
     * 解析"/db/nodes"的响应
     * nodes:[
     * {addr:xxx,ver:yyy,slaves:"a,b,c,d",level:0,shardStart:0,shardEnd:32768},
     * {addr:xxx,ver:yyy...},
     * ...
     * ]
     * @param service 调用方服务名
     * @param db 数据库名
     * @param cfg 其他配置
     * @return webdb服务dns
     */
    public static WebdbDNS parse(String service, String db, Map<String, Object> cfg) {
        List<Object> shardList = ValParser.getAsList(cfg, "nodes"); //按shardStart、level升序排序
        if(shardList == null || shardList.isEmpty()) {
            LOG.error("Invalid shards, there must be at lease one shard");
            return null;
        }
        /*
         * shardList:保证相同分片，按level倒序排列
         * select addr,shardStart,shardEnd,slaves,ver,level from dbstatus
         *  where partId=@{#tokenPartId} and dbNo=@{dbNo} and dbstatus='Y'
         *  order by dbNo,shardStart,level
         */
        return parse(service, db, shardList);
    }

    /**
     * 按照分片进行访问，分片调整后，所有调用方都必须调整
     * @param shardNo 数据库分片，可能是按data shard，也可能是按cid shard
     * @param readSlave 是否使用从库
     * @param autoSwitch 当主节点故障时，是否自动切换到从库
     * @param cid 公司id
     * @return 节点信息
     */
    public NodeAddress lookup(int shardNo, boolean readSlave, boolean autoSwitch, int cid) {
        if(shardings.length == 1 || shardNo < 0) { //没有分库的情况
            return shardings[0].lookup(readSlave, autoSwitch, cid);
        }

        for(ShardingNode n : shardings) {
            //可能存在不同level的，优先使用level靠前的实例
            if(n.isBetween(shardNo)) {
                return n.lookup(readSlave, autoSwitch, cid);
            }
        }

        return null;
    }

    @Override
    public String name() {
        return service + '.' + db; //防止与service同名
    }

    public void toString(StringBuilder sb) {
        boolean notFirst = false;
        for(ShardingNode n : shardings) {
            if(notFirst) {
                sb.append(",\n");
            }
            notFirst = true;
            n.toString(sb);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append('[');
        toString(sb);
        sb.append(']');
        return sb.toString();
    }

    /**
     * 判断host有那些slave
     * @param host 主节点
     * @return 从节点
     */
    public String[] slavesOf(String host) {
        for(ShardingNode node : shardings) {
            if(host.equals(node.mainNode().addr)) {
                return node.slaves();
            }
        }
        return new String[] {};
    }
}
