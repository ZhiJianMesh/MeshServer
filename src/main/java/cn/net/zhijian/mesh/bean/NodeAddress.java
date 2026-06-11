package cn.net.zhijian.mesh.bean;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;

/**
 * 服务节点信息，包括健康状况
 * check函数提供了检测功能
 * @author flyinmind of csdn.net
 *
 */
public class NodeAddress {
    public static final String SEG_ADDR = "addr";
    public static final String SEG_VERSION = "ver";

    public static final int NORMAL = 0;
    public static final int ABNORMAL = 1;
    
    private static final Logger LOG = LogUtil.getInstance();
    protected static final int CHECK_INTERVAL = 10;

    public final String addr; //ip地址+端口号
    //服务名称，用于调用check检查状态，
    //数据库服务固定为webdb，其他服务只有service
    public final String service;
    public final int ver; //服务版本号
    public final int level; //节点的等级，因为服务节点都是对等的，所以为0，而db的节点不一定对等
    
    volatile int state = NORMAL;
    protected volatile int nextCheckTime = 0;//1024毫秒为一个单位，不用long，是因为它有原子性问题
    protected AtomicInteger failedTimes = new AtomicInteger(0); //健康检查时，连续失败次数
    
    /**
     * @param service 服务名
     * @param addr ip地址+端口号
     * @param ver 版本号Semantic
     * @param level 等级，为Integer.MAX_VALUE时，表示跨region
     *      这种实例静态配置中可以出现，但是不会出现在当前region的bios中，
     *      因为它不会向当前region的bios注册并定时报告状态
     */
    public NodeAddress(String service, String addr, int ver, int level) {
        this.service = service;
        this.addr = addr;
        this.ver = ver;
        this.level = level;
    }
    
    public NodeAddress(String service, String addr) {
        this(service, addr, 1, 0);
    }
    
    public void reset() {
        nextCheckTime = 0;
        failedTimes.set(0);
        state = NORMAL;
    }
    
    public boolean normal() {
        return state == NORMAL;
    }
    
    public void check(int cid) {
        int cur = curTime();
        if(cur < nextCheckTime) {
            return;
        }
        Map<String, String> headers = MapBuilder.of(IConst.HEAD_CID, Integer.toString(cid));
        nextCheckTime = cur + CHECK_INTERVAL; //阻挡并发的多余check
        ServiceClient.serviceGet(addr, service, IConst.API_CHECKUP, headers, "").whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Check {}@{},failed", service, addr, e);
                return;
            }
            
            if(hr.code == RetCode.OK) {
                reset();
                LOG.info("Check {}@{},succeed", service, addr);
            } else {
                LOG.info("Check {}@{},code:{},faild", service, addr, hr.code);
            }
        }, IThreadPool.Pool);
    }
    
    public void report(boolean ok) {
        if(ok) {
            //异常恢复时，如此切回，容易导致浪涌。暂不解决
            reset(); 
            return;
        }
        
        if(failedTimes.incrementAndGet() > 1) { //连续失败两次才会将节点设为异常
            this.state = ABNORMAL;
            return;
        }

        LOG.info("State report {}@{},failed", service, addr);
        nextCheckTime = curTime() + CHECK_INTERVAL;
    }
    
    @Override
    public String toString() {
        return "{service:\"" + service + "\"," + SEG_ADDR + ":\"" + addr + "\","
                + SEG_VERSION + ":\"" + ver+ "\",state:" + state + '}';
    }
    
    public static String join(NodeAddress[] nodes, String spliter) {
        if(nodes == null || nodes.length == 0) {
            return "";
        }
        String s = "";
        for(NodeAddress node : nodes) {
            if(!s.isEmpty()) {
                s += spliter;
            }
            s += node.addr;
        }
        return s;
    }

    protected static int curTime() {
        long cur = System.currentTimeMillis() - IConst.STARTUP_AT;
        return (int)(cur >> 10);
    }
}
