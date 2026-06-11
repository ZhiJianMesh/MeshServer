package cn.net.zhijian.mesh.bean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * bios中记录meta信息的节点的dns。
 * meta库只有一个主库，其他都是从库，
 * 与staus不同，status可以环状备份
 * @author flyinmind of csdn.net
 *
 */
public class BiosDNS {
    private static final Logger LOG = LogUtil.getInstance();
    
    private volatile NodeAddress[] nodes; //通常只有一两个，所以不用list
    private volatile int mainIdx = 0;

    public BiosDNS(List<String> addrs, String oldMain) throws MeshException {
        this.refresh(addrs, oldMain);
    }

    public NodeAddress mainNode() {
        NodeAddress na = nodes[mainIdx];
        if(na.normal()) {
            return na; //能找到正常节点
        }
        
        int old = mainIdx;
        
        synchronized(this) {
            if(mainIdx != old) {//并发时mainIdx可能在其他线程被改变了
                return nodes[mainIdx];
            }
            int next = old >= (nodes.length - 1) ? 0 : (old + 1);
            initMain(nodes[old], nodes[next]);//同步网络请求，一旦走到这里，整体性能会下降
            return nodes[mainIdx];
        }
    }

    public void setMain(String mainAddr) {
        for(int i = 0; i < this.nodes.length; i++) {
            if(this.nodes[i].addr.equals(mainAddr)) {
                this.mainIdx = i;
                break;
            }
        }        
    }
    
    public String mainAddr() {
        return this.nodes[this.mainIdx].addr;
    }
    
    public NodeAddress[] slaves(String local) {
        if(nodes.length < 1) {
            return null;
        }
        NodeAddress[] slaves = new NodeAddress[nodes.length - 1];
        int n = 0;

        for(NodeAddress node : nodes) {
            //不是本机，则加为slave，用于建立同步关系，需要确保local在列表中，否则会跑越界异常
            if(!node.addr.equals(local)) {
                slaves[n++] = node;
            }
        }
        return slaves;
    }
    
    public void refresh(List<String> addrs, String main) throws MeshException {
        String addr;
        NodeAddress[] nodes = new NodeAddress[addrs.size()];
        
        int mainIdx = -1;
        for(int i = 0; i < addrs.size(); i++) {
            addr = addrs.get(i);
            nodes[i] = new NodeAddress(IConst.SERVICE_BIOS, addr, 1000, 0);
            if(addr.equals(main)) {
                mainIdx = i;
            }
        }
        if(mainIdx < 0) {
            throw new MeshException("invalid main bios");
        }
        this.mainIdx = mainIdx;
        this.nodes = nodes;
        LOG.debug("Bios {},main:{}", NodeAddress.join(nodes, ","), nodes[mainIdx].addr);
    }
    
    public boolean isBios(String addr) { //判断当前实例是不是bios实例
        for(NodeAddress node : nodes) {
            if(node.addr.equals(addr)) {
                return true;
            }
        }
        return false;
    }
    
    private void initMain(NodeAddress old, NodeAddress next) {
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_BIOS)
                .url("/mainbios")
                .appendPara("act", "set")
                .appendPara("old", old.addr, false)
                .appendPara("next", next.addr, false);

        try {
            HandleResult hr = BiosClient.get(next, req).get(3, TimeUnit.SECONDS);
            if(hr.code != RetCode.OK) {
                LOG.warn("Fail to set mainBios of {}, result:{}", next.addr, hr.brief());
                return;
            }
            handleResp(next, hr.data);
        } catch (Exception e) {
            LOG.warn("Fail to set mainBios of {}", next.addr, e);
        }
    }
    
    /**
     * 从文件加载partition.cfg时，不知道当前选择哪一个bios作为主bios，
     * 先调用此接口获得最新的主bios编号
     */
    public void initMain() {
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_BIOS)
            .url("/mainbios").appendPara("act", "get");
         
        for(NodeAddress node : nodes) {//逐个同步查询，取到正确的就停止
            try {
                HandleResult hr = BiosClient.get(node, req).get(3, TimeUnit.SECONDS);
                if(hr.code != RetCode.OK) {
                    LOG.warn("Fail to get mainBios of {}, result:{}", node.addr, hr.brief());
                    continue;
                }
                if(handleResp(node, hr.data)) {
                    return;
                }
            } catch (Exception e) {
                LOG.warn("Fail to get mainBios of {}", node.addr, e);
            }
        }
        mainIdx = 0; //都无法获得的情况，强制为0
    }
    
    boolean handleResp(NodeAddress node, Map<String, Object> data) throws MeshException {
        String main = ValParser.getAsStr(data, "main");
        if(StringUtil.isEmpty(main)) {
            LOG.warn("Fail to get mainBios from {}, invalid main", node.addr);
            return false;
        }
        List<String> list = ValParser.getAsStrList(data, "list");
        if(list == null || list.isEmpty()) {
            LOG.warn("Fail to get bios list from {}, invalid list", node.addr);
            return false;
        }
        refresh(list, main);
        return true;
    }
    
    public List<String> toList() {
        List<String> ss = new ArrayList<>(nodes.length);
        for(NodeAddress node : nodes) {
            ss.add(node.addr);
        }
        return ss;
    }
    
    @Override
    public String toString() {
        String s = "[";
        for(int i = 0; i < nodes.length; i++) {
            if(i > 0) {
                s += ',';
            }
            s += '"' + nodes[i].addr + '"';
        }
        return s + ']';
    }
}
