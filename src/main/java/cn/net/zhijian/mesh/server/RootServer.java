package cn.net.zhijian.mesh.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo.ServiceType;
import cn.net.zhijian.mesh.frm.method.ApiMethod;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

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
    private static final Logger LOG = LogUtil.getInstance();
    private static final int BILLING_INTERVAL = 600 * 1000;
    private static final Map<String, Balance> billings = new ConcurrentHashMap<>(); //key:service+'_'+cid

    /**
     * 加载所有服务的配置信息，并初始化omagent的接口
     * @param workDir 项目运行的根目录
     *  如果有，则用omPwd生成token，从bios主实例获取
     */
    public RootServer(String workDir) throws MeshException {
        super(workDir);
    }
    
    /**
     * 增加计费，不管余额是否充足
     * 当上报消费量之后，得知余额不足，则会将其置为无效，此后就不能使用。
     * 如果重启，未上报的计费会消失。因为计费只针对运行在云侧的服务，所以此种缺陷可以容忍。
     * 为了简化设计，尽量由平台承担损失，减少用户损失。
     * @param cid 公司id
     * @param service 服务
     * @return 计费结果，true表示通过
     */
    private Balance getBalance(int cid, ServiceInfo service) {
        String k = service.name + '_' + cid;
        Balance balance = billings.get(k);
        if(balance == null) {
            balance = new Balance(service, cid);
            billings.put(k, balance);
        }
        balance.val.incrementAndGet();
        return balance;
    }

    
    /**
     * 退出时，强制计费一遍
     */
    @Override
    protected CompletableFuture<Void> billingAll() {
        CompletableFuture<HandleResult> cf;
        List<CompletableFuture<HandleResult>> tasks = new ArrayList<>();
        for(Balance b : billings.values()) {
            cf = b.billing(System.currentTimeMillis(), true);
            if(cf != null) {
                tasks.add(cf);
            }
        }
        return CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[]{}));
    }

    @Override
    protected boolean checkBalance(ApiMethod am, AbsServerRequest req) {
        /* 
         * 公司类型的服务运行在根环境，需要计费，
         * public接口、或在私有云中，不计费
         */
        int cid = req.cid();
        if(am.serviceInfo.type == ServiceType.COMPANY && cid > ROOT_COMPANY_ID) {
            Balance balance = getBalance(cid, am.serviceInfo);
            if(!balance.valid) {
                LOG.warn("Fail to bill {}.{}", am.serviceInfo.name, cid);
                return false;
            }
            balance.billing(req.reqTime, false);
        }
        return true;
    }
    
    private static class Balance {
        final ServiceInfo si;
        final int cid;

        final AtomicInteger val = new AtomicInteger(0); //当前用量
        long nextBillAt = System.currentTimeMillis(); //下次计费时间
        volatile boolean valid = true;
        
        Balance(ServiceInfo si, int cid) {
            this.si = si;
            this.cid = cid;
        }
        
        private CompletableFuture<HandleResult> billing(long cur, boolean force) {
            if(!force && cur < this.nextBillAt) {
                return null;
            }
            this.nextBillAt = System.currentTimeMillis() + BILLING_INTERVAL;
            int v = this.val.get();
            if(v == 0 && this.valid) {
                return null;//计费已失效时，即使用量为0也需上报，因为此期间可能充值了，上报后获得当前的新余量
            }
            
            ServiceReqBuilder req = new ServiceReqBuilder(si, SERVICE_COMPANY)
                    .url("/service/billing")
                    .traceId(this.si.name)
                    .cid(this.cid)
                    .appToken("*")
                    .put("service", this.si.name)
                    .put("cid", this.cid)
                    .put("val", v);
            return ServiceClient.servicePost(req).whenCompleteAsync((hr, e) -> {
                if(e != null) {
                    LOG.error("Fail to bill {}.{}", si.name, cid, e);
                    return;
                }
                
                if(hr.code != RetCode.OK) {
                    LOG.error("Fail to bill {}.{}, {}", si.name, cid, hr.brief());
                    this.valid = hr.code != RetCode.NOT_EXISTS;
                } else {
                    long left = ValParser.getAsLong(hr.data, "balance");
                    this.valid = left > 0;//会有一些损失，但是最长损失10分钟用量
                    this.val.addAndGet(-v); //即使无余量，也要减去已上报的量
                }
            }, Pool);
        }
    }
}