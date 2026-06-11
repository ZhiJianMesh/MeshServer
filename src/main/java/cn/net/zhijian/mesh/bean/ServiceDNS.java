package cn.net.zhijian.mesh.bean;

import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.frm.abs.AbstractDNS;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 服务节点发现，只支持一致性hash方式，当选中节点故障时，则从这个节点的后面逐个尝试，
 * 找到正常的则使用，发现有异常的，发起check，如果check正常，会改变它的状态，继续工作。
 * ServiceDNS会缓存在ServiceClient中，每5分钟从bios更新一次，
 * 如果更新失败，会继续使用缓存内容。
 * @author flyinmind of csdn.net
 *
 */
public final class ServiceDNS extends AbstractDNS {
    private static final Logger LOG = LogUtil.getInstance();
    private static final int VIRTUAL_NODES_NUM = 64;
    
    public final String service;
    public final NodeAddress[] realNodes; //原始的地址列表，用在toString中
    public final NodeAddress[] virtualNodes; //转换过后的虚拟地址列表，用于一致性hash运算，避免不均衡

    private int cur = 0;
    private final int localNo; //记录当前实例的编号，如果不存在则为-1，用于优化请求，优先使用本实例

    /**
     * @param service 服务名称
     * @param nodes 节点
     * @param expiresIn 多少秒之后过期，<=0，表示永不过期
     */
    private ServiceDNS(String service, NodeAddress[] nodes, int expiresIn) {
        super(expiresIn);
        int localNo = -1;
        String local = ChannelConfig.instance().localHttpAddr();
        for(int i = 0; i < nodes.length; i++) {
            if(nodes[i].addr.equals(local)) {
                localNo = i;
            }
        }
        this.realNodes = nodes;
        this.virtualNodes = virtualNodes(nodes);
        this.service = service;
        this.localNo = localNo;
    }

    /**
     * @param service 服务名称
     * @param addrs 节点ip+端口
     * @param expiresIn 多少秒之后过期，<=0，表示永不过期
     */
    public static ServiceDNS create(String service, String[] addrs, int expiresIn) {
        NodeAddress[] nodes = new NodeAddress[addrs.length];
        for(int i = 0; i < addrs.length; i++) {
            nodes[i] = new NodeAddress(service, addrs[i], 1000, 0);
        }
        return new ServiceDNS(service, nodes, expiresIn);
    }

    public static ServiceDNS create(String service, String[] addrs) {
        return create(service, addrs, DEFAULT_EXPIRES_IN);
    }

    /**
     * 模仿一致性hash，使用64个虚拟节点，将真实节点均匀分散到64个虚拟节点中
     * @param realNodes 实际的节点
     * @return 虚拟节点数组
     */
    private static NodeAddress[] virtualNodes(NodeAddress[] realNodes) {
        final int n;
        if((n = realNodes.length) >= VIRTUAL_NODES_NUM) {
            return realNodes;
        }

        NodeAddress[] virtualNodes = new NodeAddress[VIRTUAL_NODES_NUM];

        int filledNum = 0;
        int foreSn = 0;
        int sn;

        /*
         * 目标：a）节点分布基本均衡；b）增删节点，原有节点在数组中位置很少改变；
         * 1)分成多轮均衡，直到将实际节点分散填满VIRTUAL_NODES_NUM个虚拟节点；
         * 2)每轮中，根据节点的addr计算出sn，其中的turn*4是尝试出来的，让节点分布更加均匀，没有理论证明；
         * 3)当增加或减少节点时，只有极少节点的分布位置发生改变，当减少节点时，不至于将其他正常节点冲垮。
         */
        for(int turn = 0; filledNum < VIRTUAL_NODES_NUM; turn++) {
            for(int i = 0; i < n; i++) {
                sn = ValParser.absInt((realNodes[i].addr + (turn*4)).hashCode()) % VIRTUAL_NODES_NUM;
                if(virtualNodes[sn] == null) {
                    virtualNodes[sn] = realNodes[i];
                    foreSn = sn;
                    filledNum++;
                    continue;
                }

                sn = (1 + foreSn) % VIRTUAL_NODES_NUM; //从前一个占位的后面继续插入
                for(int j = 0; j < VIRTUAL_NODES_NUM - 1; j++) {
                    if(virtualNodes[sn] == null) {
                        virtualNodes[sn] = realNodes[i];
                        filledNum++;
                        foreSn = sn;
                        break;
                    }
                    sn = (sn + 1) % VIRTUAL_NODES_NUM;
                }
            }
        }
        return virtualNodes;
    }

    /**
     * 解析"/service/nodes"的响应内容
     * @param service 服务名
     * @param cfg nodes[{addr:"xxxx",ver:"xxxx"},...]
     * @return 服务dns
     */
    public static ServiceDNS parse(String service, Map<String, Object> cfg) {
        int num;
        List<Object> addrList = ValParser.getAsList(cfg, "nodes");
        if(addrList == null || (num = addrList.size()) == 0) {
            LOG.error("Invalid addrs, there must be at lease one addr");
            return null;
        }

        NodeAddress[] nodes = new NodeAddress[num];
        for(int i = 0; i < num; i++) {
            Map<String, Object> one = ValParser.parseObject(addrList.get(i));
            String addr = ValParser.getAsStr(one, NodeAddress.SEG_ADDR).trim();
            int ver = ValParser.getAsInt(one, NodeAddress.SEG_VERSION);
            nodes[i] = new NodeAddress(service, addr, ver, 0); //服务实例的节点都是对等的，所以level都为0
        }

        return new ServiceDNS(service, nodes, DEFAULT_EXPIRES_IN);
    }

    /**
     * 一致性hash方式访问
     * @param id 请求方标识，为了尽量将一个用户的请求发送到同一个实例中，
     *    这样服务提供方可以使用本地缓存解决很多问题
     * @param cid 公司id
     * @return 节点信息
     */
    public NodeAddress lookup(int id, int cid) {
        if(localNo >= 0) {
            if(realNodes[localNo].normal()) {
                return realNodes[localNo]; //优先使用本实例上的服务
            }
        }
        int slot = ValParser.absInt(id) % virtualNodes.length;
        if(virtualNodes[slot].normal()) {
            return virtualNodes[slot]; //绝大部分情况，在这里返回
        }

        /*
         * 如果选中的节点发生异常，则从这个节点的后面寻找合适的。
         * 在寻找的过程中，如果发现了异常节点，会启动check，
         * check成功后，又会将节点置为正常，继续工作
         */
        int num = virtualNodes.length;
        int okOne = -1;
        int no;
        for(int i = 1/*回到第一个再试，没意义*/; i < num; i++) {
            no = (slot + i) % num;
            if(virtualNodes[no].normal()) {
                if(okOne == -1) {
                    okOne = no;
                }
                continue;
            }
            /*
             * 1）异步方式check，不会堵塞当前流程；
             * 2）服务不被请求，则不会检测；
             * 3）即使服务被频繁请求，也至少每隔10秒检测一次；
             */
            virtualNodes[no].check(cid);
        }

        if(okOne >= 0) {
            return virtualNodes[okOne];
        }

        LOG.info("Fail to get nodes of {}", service);
        
        return null; //全部异常
    }

    /**
     * 轮询方式访问
     * @param cid 公司id
     * @return 节点
     */
    public synchronized NodeAddress lookup(int cid) {
        int len = realNodes.length;
        NodeAddress n = null;
        for(int i = 0; i < len; i++, cur++) {
            if(cur >= len) {
                cur = 0;
            }
            if(realNodes[cur].normal()) {
                n = realNodes[cur];
                break;
            }
            /*
             * 1）异步方式check，不会堵塞当前流程；
             * 2）服务不被请求，则不会检测；
             * 3）即使服务被频繁请求，也至少每隔10秒检测一次；
             */
            realNodes[cur].check(cid);
        }

        return n; //可能全部节点都异常
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"name\":\"").append(name()).append("\",\"addrs\":[");
        for(int i = 0; i < realNodes.length; i++) {
            NodeAddress n = realNodes[i];
            if(i > 0) {
                sb.append(',');
            }
            sb.append("{\"")
            .append(NodeAddress.SEG_ADDR).append("\":\"")
            .append(n.addr).append("\",\"")
            .append(NodeAddress.SEG_VERSION).append("\":")
            .append(n.ver)
            .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Override
    public String name() {
        return service;
    }
}
