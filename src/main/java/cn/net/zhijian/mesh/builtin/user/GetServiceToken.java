package cn.net.zhijian.mesh.builtin.user;

import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 用户token换服务级token，此token可以通过user.very校验
 * 用户token(UT)不同于用户服务级token(UST)，
 * UT只能用于访问user服务，UST只能用于访问服务，每个服务一个UST。
 * 有了UT，才能向user服务申请UST，然后用UST访问服务。
 * UST在verify时，如果UT已过期或失效，则所有UST也失效。
 * UST不支持refresh，它自身永不过期，除非UT过期或退出。
 * 
 * @author flyinmind of csdn.net
 *
 */
public final class GetServiceToken extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    private static final long MAX_REFRESH_TIME = 10 * 60 * 1000;
    private static final Set<String> services = new HashSet<>();
    private static final Set<String> cloudServices = new HashSet<>();
    private static long refreshAt = 0;
    
    public GetServiceToken(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String service = req.getString("service");
        CompletableFuture<HandleResult> cf;
        int cid = req.cid();
        if(!services.contains(service) && !cloudServices.contains(service)) { //分配token之前，先判断系统是否有此服务
            ServiceInfo caller = serviceInfo();
            ServiceReqBuilder builder = new ServiceReqBuilder(caller, SERVICE_BIOS)
                    .traceId(req.traceId)
                    .cid(cid)
                    .url("/service/activeNodeNum")
                    .appToken("*")
                    .appendPara("partId", Integer.toString(PartitionConfig.instance().partition))
                    .appendPara("service", service); //判断服务是否有节点在正常运行
            cf = BiosClient.get(builder).thenComposeAsync(hr -> {
                if(hr.code == RetCode.OK && ValParser.getAsInt(hr.data, "nodeNum") > 0) {
                    services.add(service);
                    return futureResult();
                }
                return judgeCloudServices(req, service); //判断云侧是否开启了服务
            }, Pool);
        } else {
            cf = futureResult();
        }
        
        return cf.thenComposeAsync(hr -> {
            if(hr.code  != RetCode.OK) {
                return CompletableFuture.completedFuture(hr);
            }

            /*
             * 如果调用公有云的公司级服务，则使用私有云私钥生成token。
             * 此类token在company服务中验证
             * 如果公司服务使用的是云端的，cloud也为true
             */
            boolean isCloud = (!services.contains(service) && cloudServices.contains(service))
                              || req.getBool("isCloud");
            return UserBase.getTokenWorker(cid, req.serviceInfo(), isCloud).thenApplyAsync(tw -> {
                AccessToken at = req.token();
                //与用户服务的token保持一致，保证用户服务token过期，其他都跟着过期
                long expiresAt = at.expiresAt();

                //生成用户服务级token
                AccessToken serviceToken = tw.create(PartitionConfig.instance().partition,
                        at.caller/*userid*/, service, expiresAt, 
                        at.ext/*accout,cid*/, TOKENTYPE_USER);
                String tokenStr = serviceToken.generate();
                resp.put(SEG_ACCESS_TOKEN, tokenStr);
                resp.put(SEG_EXPIRES_AT, expiresAt);
                resp.put(SEG_TOKEN_TYPE, "session");
                
                long cacheId = UserBase.getCacheId(req.cid(), tokenStr);
                UserBase.UserTokenCache.put(cacheId, serviceToken);

                return new HandleResult(RetCode.OK, resp);
            }, Pool);
        }, Pool);
    }
    
    private CompletableFuture<HandleResult> judgeCloudServices(AbsServerRequest req, String service) {
        long interval = System.currentTimeMillis() - refreshAt;
        if(interval < MAX_REFRESH_TIME) { //非高频请求，未作互斥优化
            if(cloudServices.contains(service)) {
                return futureResult();
            }
            return futureResult(RetCode.NOT_SUPPORTED_FUNCTION, "service not opened in cloud");
        }
        
        int cid = req.cid();
        //公有云环境获取已安装服务列表，如果服务存在，则生成companytoken
        ServiceReqBuilder reqBuilder = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
                .url("/api/service/opened?cid=" + cid)
                .traceId(IConst.SERVICE_COMPANY + '_' + cid)
                .cid(cid);
        return ServiceClient.cloudGet(reqBuilder).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.warn("Can't get opened service of {}, result:{}", cid, hr.brief());
                return CompletableFuture.completedFuture(hr);
            }
            List<String> sl = ValParser.getAsStrList(hr.data, "list");
            if(sl != null) {
                cloudServices.clear();
                cloudServices.addAll(sl);
                refreshAt = System.currentTimeMillis();
                LOG.debug("Services of company({}):{}", cid, StringUtil.joinArray(sl, ","));
            }
            if(cloudServices.contains(service)) {
                return futureResult();
            }
            return futureResult(RetCode.NOT_SUPPORTED_FUNCTION, "service not opened in cloud");
        });     
    }
}