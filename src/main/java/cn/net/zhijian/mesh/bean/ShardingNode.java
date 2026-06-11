package cn.net.zhijian.mesh.bean;

import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 数据库分片节点信息。
 * 每个分片可以独立设置从库
 * @author flyinmind of csdn.net
 *
 */
public final class ShardingNode implements IDBConst {
    private static final Logger LOG = LogUtil.getInstance();

    public final int start;//分片起始编号，包括
    public final int end; //分片结束编号，不包括，cidsharding与datasharding，不能同时存在
    //master or slave
    //master可以作为其他实例的备用实例，比如互主备的情况
    //但是slave只能作为备用实例
    public final String mode;
    private int cur = 0; //当前主节点，默认指向第一个主节点
    private final AtomicInteger curSlave = new AtomicInteger(0); //默认指向第0个，主节点
    //[主,备1,备2...]，备库通常1-3个，不宜过多，过多时主库同步负担重
    //如果level为Integer.MAX_VALUE，则为远程从节点，不可切换到它上面
    private final List<DBNodeAddress> nodes;

    /**
     *
     * @param service 服务名称
     * @param db 数据库名称
     * @param ver 版本
     * @param start 开始分片号（包括）
     * @param end 结束分片号（不包括）
     * @param level 节点的级别，越低越优先使用，比如master的级别为0，远程节点的级别为MAX_VALUE
     * @param nodes 实例
     */
    public ShardingNode(int ver, int start, int end, int level, DBNodeAddress[] nodes, String mode) {
        this.start = start;
        this.end = end;
        this.nodes = new CopyOnWriteArrayList<>(nodes);
        this.mode = mode;
    }

    /**
     * 解析"/db/nodes"的响应，本函数解析nodes中的一个节点
     * addr,shardStart,shardEnd,slaves,ver,level
     * @param service 服务名
     * @param db 数据库名
     * @param cfg 其他配置
     * @return 分片节点
     */
    public static ShardingNode parse(String service, String db, Map<String, Object> cfg) {
        String addr = ValParser.getAsStr(cfg, NodeAddress.SEG_ADDR, null); //server addr
        if(StringUtil.isEmpty(addr)) {
            LOG.error("Invalid sharding node in {}.{}, addr is null", service, db);
            return null;
        }
        int start = ValParser.getAsInt(cfg, CFG_SEG_SHARDSTART);
        int end = ValParser.getAsInt(cfg, CFG_SEG_SHARDEND);
        if(start < 0 || end > MAX_SHARDING_NUM || end <= start) {
            LOG.error("Invalid sharding is set, end<=start or start<0 or end>{},in {}.{}",
                    MAX_SHARDING_NUM, service, db);
            return null;
        }

        //WebdbDNS中不关心slaves，只有在同步服务中才需要
        int ver = ValParser.getAsInt(cfg, NodeAddress.SEG_VERSION);
        int level = ValParser.getAsInt(cfg, CFG_SEG_LEVEL, 0);
        String slaves = ValParser.getAsStr(cfg, CFG_SEG_SLAVES);
        String mode = "S".equals(ValParser.getAsStr(cfg, "status")) ? DB_SLAVE : DB_MASTER;
        if(StringUtil.isEmpty(slaves)) {
            DBNodeAddress[] nodes = new DBNodeAddress[] {new DBNodeAddress(service, db, addr, ver, level)};
            return new ShardingNode(ver, start, end, level, nodes, mode);
        }
        String[] ss = slaves.split(","); //可能包括了跨region的从实例，validateSlaves中会删除它们
        DBNodeAddress[] nodes = new DBNodeAddress[1 + ss.length];
        nodes[0] = new DBNodeAddress(service, db, addr, ver, level); //第一个是主实例

        int i = 1;
        for(String s : ss) {
            nodes[i++] = new DBNodeAddress(service, db, s.trim(), ver, level + 1);
        }
        return new ShardingNode(ver, start, end, level, nodes, mode);
    }

    /**
     * 找到一个可以正常使用的节点
     * 在读操作时，可以指定是否使用slave；
     * 如果主节点异常，则在从节点中寻找一个从节点替代主节点。
     * @param readSlave 如果是一个读sql，可以传递true
     * @param autoSwitch 当主节点故障时，是否自动切换到备用节点
     * @param cid 公司id
     * @return 符合要求的节点
     */
    public NodeAddress lookup(boolean readSlave, boolean autoSwitch, int cid) {
        int num = nodes.size();
        if(num == 0) {
            return null;
        }

        NodeAddress node;
        if(readSlave) { //查询的情况下，可以用slave
            int slave;
            for(int i = 0; i < num; i++) {
                slave = curSlave.getAndIncrement(); //尽量均衡开，而不是只查询同一个slave
                if(slave >= num) {
                    curSlave.set(0);/*第0个是main，也可能被查询*/
                    slave = 0;
                }
                node = nodes.get(slave);
                if(node.normal()) { //如果不正常，则返回可写实例
                    return node;
                }
            }
        }

        DBNodeAddress dna = nodes.get(cur);
        if(dna.normal()) {
            return dna; //绝大部分情况在此返回
        }

        if(!autoSwitch) { //不容许自动切换的情况，直接失败
            return null;
        }

        //main故障的情况下，切换到slave
        LOG.warn("Fail to find main,then try to pick an alternative slave");
        int oldOne = cur;
        synchronized (this) {//只需一个线程做即可
            if(oldOne != cur) { //多线程情况下，后执行的线程走到这里时，cur应该已改变
                return nodes.get(cur);
            }
            int okOne = -1;
            for (int i = 0, no = cur + 1; i < num - 1/*当前不用再尝试*/; i++, no++) {
                if (no >= num) {
                    no = 0;
                }
                node = nodes.get(no);
                if (node.normal()) {
                    if(okOne < 0) {
                        okOne = no; //先记下，因为还要继续探测其他节点
                    }
                } else {
                    node.check(cid); //异步请求，不会堵塞当前线程
                }
            }
            if (okOne >= 0) {
                cur = okOne;
                return nodes.get(cur);
            }
        }
        return null;
    }

    public boolean isBetween(int shard) {
        return shard >= start && shard < end;
    }

    public void toString(StringBuilder sb) {
        NodeAddress node = nodes.get(0);
        sb.append("{\n\"").append(CFG_SEG_LEVEL).append("\":").append(node.level).append(",\n\"")
          .append(NodeAddress.SEG_ADDR).append("\":\"").append(node.addr).append("\",\n\"")
          .append(NodeAddress.SEG_VERSION).append("\":").append(node.ver).append(",\n\"")
          .append(CFG_SEG_SHARDSTART).append("\":").append(this.start)
          .append(",\n\"").append(CFG_SEG_SHARDEND).append("\":").append(this.end);

        if(this.nodes.size() > 1) {
            int num = 0;
            sb.append(",\n\"").append(CFG_SEG_SLAVES).append("\":\"");
            for(int i = 1; i < this.nodes.size(); i++) {
                if(num > 0) {
                    sb.append(',');
                }
                sb.append(this.nodes.get(i).addr);
                num++;
            }
            sb.append('"');
        }
        sb.append("\n}");
    }

    public DBNodeAddress mainNode() {
        return nodes.get(0);
    }

    public boolean isSameSharding(ShardingNode sn) {
        return sn.start == this.start && sn.end == this.end;
    }
    
    /**
     * slaves中可能包括跨region的备库，这些实例不宜访问
     * 本region的从实例都会定期上报到bios，但是跨region实例不会上报到本region，所以自然从bios中查不到
     * 此函数实现剔除掉在slaves列表中，但是当前未上报到bios中的实例。
     * 如果是本region的实例，但是尚未启动，会被剔除掉，
     * webdns会每隔10分钟刷新，当它启动后，下一次刷新时会将它加载进来
     * @param nodes 当前存在的所有实例
     */
    public void validateSlaves(List<ShardingNode> nodes) {
        NodeAddress v;
        boolean valid;
        for(int i = this.nodes.size() - 1; i > 0/*第一个是主实例，不用比较*/; i--) {
            v = this.nodes.get(i);
            valid = false;
            for(ShardingNode n : nodes) {
                if(n.nodes.get(0).addr.equals(v.addr)
                   && n.start == this.start && n.end == this.end) {
                    valid = true;
                    continue;
                }
            }
            
            if(!valid) {
                LOG.warn("Node {} removed from slaves of {}", v.addr, this.nodes.get(0).addr);
                this.nodes.remove(i);
            }
        }
    }

    public String[] slaves() {
        if(this.nodes.size() < 2) {
            return new String[] {};
        }

        String[] slaves = new String[this.nodes.size() - 1];
        for(int i = 1; i < this.nodes.size(); i++) {
            slaves[i - 1] = this.nodes.get(i).addr;
        }

        return slaves;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(1024);
        toString(sb);
        return sb.toString();
    }
}
