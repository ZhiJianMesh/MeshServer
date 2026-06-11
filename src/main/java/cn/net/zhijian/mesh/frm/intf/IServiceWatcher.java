package cn.net.zhijian.mesh.frm.intf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.method.ApiMethod;
import cn.net.zhijian.mesh.prot.http.NullServerRequest;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 服务看护类
 * 完成服务启动时，需要做的一些公共逻辑，比如加载一些参数。
 * 在service.cfg中指定加载类，如果在classpath中，则直接加载，不在，则从服务路径下的jar包中加载。
 * 在serviceinfo读取service.cfg时被加载执行，
 * 文件版本的IServiceInit都是从service.cfg中加载附加的配置项，
 * 如果有独立的密码箱，则应该从密码箱加载响应的启动配置，比如密码本、config服务的userkey、cipher
 *
 * @author flyinmind of csdn.net
 *
 */
public interface IServiceWatcher extends IConst, IThreadPool {
    String SEG_PARTID = "partId";
    String SEG_ADDR = "addr";
    String SEG_SERVICE = "service";
    String SEG_SERVICES = "services";
    String SEG_VER = "ver";

    /**
     * 加载服务配置之前执行，
     * 此处的初始化工作不能依赖其他服务，因为在同一实例的情况下，其他服务可能还没启动
     * @param si 服务信息
     * @param server 虚拟服务器
     * @param pwd 首次启动初始化时提供的密码，OM密码或公司密码
     *   主bios首次启动需要om密码，keystore服务需要公司密码，其他都可以，但是建议使用om密码
     * @return 异步处理结果
     */
    CompletableFuture<HandleResult> beforeLoad(IServiceServer server, ServiceInfo si, String pwd);

    /**
     * 加载服务配置之后执行，这时服务已经启动
     * 服务可以接受http请求了，比如从bios中读取配置信息等
     * 但是有一些服务还要在此做一些其他的初始化，所以部分服务仍然不可以调用
     * @param server 虚拟服务器
     * @param si 服务信息
     * @return 异步处理结果
     */
    CompletableFuture<HandleResult> afterLoad(IServiceServer server, ServiceInfo si, String pwd);

    /**
     * 定期执行的接口，比如实现状态检测等
     * @param server 虚拟服务器
     * @param si 服务信息
     */
    void watch(IServiceServer server, ServiceInfo si);

    /**
     * 在所有服务afterload完成后执行，这时基础服务的准备工作已结束，可以调用。
     * 之所以不合并到{@code}afterLoad{@code}中，
     * 是因为多个服务同时启动时，被依赖服务可能还没有准备好。
     * @param si 服务信息
     * @param server 虚拟服务器
     * @return 异步处理结果
     */
    CompletableFuture<HandleResult> startup(IServiceServer server, ServiceInfo si);

    /**
     * 一般用于向bios汇报结束状态
     * @param si 服务信息
     * @param server 虚拟服务器
     * @return 异步处理结果
     */
    CompletableFuture<HandleResult> destroy(IServiceServer server, ServiceInfo si);

    /**
     * 默认服务看护类
     * @author flyinmind of csdn.net
     *
     */
    class DefaultServiceWatcher implements IServiceWatcher {
        private static final Logger LOG = LogUtil.getInstance();
        
        /**
         * 从bios获取服务的密钥对
         * @param si 服务
         * @param pwd 公司密码或运维totp密码
         * @return 公司密钥对
         */
        public CompletableFuture<EccKeyPair> getKey(ServiceInfo si, String pwd) {
            UrlPathInfo urlInfo = new UrlPathInfo(SERVICE_URL_API)
                    .push("service")
                    .push("getKey")
                    .appendPara("service", si.name, false);

            //向主bios实例请求，从bios实例可能并未启动
            return BiosClient.omGet(si, urlInfo, pwd).thenApplyAsync(hr -> {
                if(hr.code != RetCode.OK) {
                    LOG.error("Fail to get service key of {},result:{}", si.name, hr.brief());
                    return null;
                }
                String key = ValParser.getAsStr(hr.data, "key");
                try {
                    return EccKeyPair.parse(key);
                } catch (Exception e) {
                    LOG.error("Fail to parse {}'s keypair", si.name, e);
                }
                return null;
            });
        }
        
        @Override
        public CompletableFuture<HandleResult> beforeLoad(IServiceServer server, ServiceInfo si, String pwd) {
            LOG.info("{}.watcher.beforeLoad(),token:{}", si.name,  si.tokenWorker.isEmpty());
            
            if(!si.tokenWorker.isEmpty()) {
                return CompletableFuture.completedFuture(si.initLocalDb(server.homeDir()));
            }

            if(StringUtil.isEmpty(pwd)) {
                return HandleResult.future(RetCode.WRONG_PARAMETER, "no password");
            }
            return getKey(si, pwd).thenApplyAsync(ecKey -> {
                if(ecKey == null) {
                    return new HandleResult(RetCode.INTERNAL_ERROR, "no service key");
                }
                si.tokenWorker.setKey(ecKey);
                return si.initLocalDb(server.homeDir());
            });
        }

        @Override
        public CompletableFuture<HandleResult> afterLoad(IServiceServer server, ServiceInfo si, String pwd) {
            LOG.info("{}.watcher.afterLoad()", si.name);
            if(!StringUtil.isEmpty(pwd)) {
                return init(si, pwd).thenComposeAsync(hr -> {
                    if(hr.code != RetCode.OK) {
                        LOG.error("Fail to call init for service {},result:{}", si.name, hr.brief());
                        return CompletableFuture.completedFuture(hr);
                    }
                    return serviceReport(si);
                }, Pool);
            }
            return serviceReport(si);
        }

        @Override
        public void watch(IServiceServer server, ServiceInfo si) {
        }

        @Override
        public CompletableFuture<HandleResult> startup(IServiceServer server, ServiceInfo si) {
            LOG.info("{}.watcher.startup()", si.name);
            /*
             * 启动时执行一次初始化。
             * 初始化操作必须是可重入的，在多实例的情况，每个实例每次启动都会执行一次
             */
            List<ApiMethod> inits = si.getInitApis();
            if(inits != null && !inits.isEmpty()) {
                AccessToken token = si.tokenWorker.create(
                        PartitionConfig.instance().partition, SERVICE_BACKEND, si.name,
                        AccessToken.EXT_FEATURE_ALL + CompanyInfo.instance().id,
                        IOAuth.TOKENTYPE_SERVICE);
                Map<String, String> headers = Map.of(
                    HEAD_CID, Integer.toString(CompanyInfo.instance().id),
                    HEAD_TRACE_ID, si.name + "_init",
                    HEAD_ACCESS_TOKEN, token.generate()
                );
                AbsServerRequest nullReq = new NullServerRequest(
                        METHOD_GET, "init",
                        headers, new HashMap<>(), si, token);
                AbsServerResponse nullResp = nullReq.createResponse();
                for(ApiMethod am : inits) {
                    LOG.info("Call {}.init `{}`", si.name, am.apiInfo.url);
                    am.execute(nullReq, nullResp);
                }
            }

            return HandleResult.future();
        }
        
        CompletableFuture<HandleResult> serviceReport(ServiceInfo si) {
            //纯端侧页面或实例内置的服务，无需上报给service
            if(!si.hasApi) {
                return HandleResult.future();
            }

            String addr = ChannelConfig.instance().localHttpAddr();
            ServiceReqBuilder builder = new ServiceReqBuilder(si, SERVICE_BIOS)
                .url("/status/srvinit")
                .traceId(si.name)
                .cid(LOCAL_COMPANY_ID)
                .put(SEG_PARTID, PartitionConfig.instance().partition)
                .put(SEG_SERVICE, si.name)
                .put(SEG_ADDR, addr)
                .put(SEG_VER, si.version)
                .appToken("*");

            return BiosClient.post(builder).whenCompleteAsync((hr, e) -> {
                if(e != null) {
                    LOG.error("Fail to call srvinit for service {}", si.name, e);
                    return;
                }
                if(hr.code != RetCode.OK) {
                    LOG.error("Fail to call srvinit {} node@{} to bios,result:{}", si.name, addr, hr.brief());
                } else {
                    LOG.debug("Success to call srvinit {} node@{} to bios", si.name, addr);
                }
            }, Pool);
        }

        @Override
        public CompletableFuture<HandleResult> destroy(IServiceServer server, ServiceInfo si) {
            //纯端侧页面或内置的服务
            if(si.tokenWorker == null) {
                return HandleResult.future();
            }

            String addr = ChannelConfig.instance().localHttpAddr();
            ServiceReqBuilder builder = new ServiceReqBuilder(si, SERVICE_BIOS)
                    .url("/status/srvremove")
                    .cid(CompanyInfo.instance().id) //随便一个都可以
                    .traceId(si.name)
                    .appendPara(SEG_PARTID, Integer.toString(PartitionConfig.instance().partition))
                    .appendPara(SEG_SERVICE, si.name)
                    .appendPara(SEG_ADDR, addr)
                    .appToken("*");

            return BiosClient.delete(builder).whenCompleteAsync((hr, e) -> {
                if(e != null) {
                    LOG.error("Fail to remove {} node@{} in bios", si.name, addr, e);
                    return;
                }
                if(hr != null && hr.code != RetCode.OK) {
                    LOG.error("Fail to remove {} node@{} in bios,result:{}", si.name, addr, hr.brief());
                } else {
                    LOG.info("Success to remove {} node@{} in bios", si.name, addr);
                }
            }, Pool);
        }
        
        /**
         * 在bios中注册服务、在webdb中初始化数据库
         * @param si 服务名称
         * @param pwd om密码或公司密码，不用sha256
         * @return 成功则返回true，否则返回false
         */
        private CompletableFuture<HandleResult> init(ServiceInfo si, String pwd) {
            List<Object> dbDefines = si.loadDbDefines();
            List<Object> dds = new ArrayList<>();
            if(!dbDefines.isEmpty()) { //存在时，才需初始化数据库
                for(Object o : dbDefines) { //删除不必要的字段，用于告知bios服务可以使用的数据库，用在oAuth2中
                    Map<String, Object> d = ValParser.parseObject(o);
                    dds.add(MapBuilder.of("name", d.get("name"), "type", d.get("type")));
                }
            }
            
            CompanyInfo ci = CompanyInfo.instance();
            //注册服务，并且添加必须的依赖项
            return si.register(ci.id, dds, pwd).thenComposeAsync(hr -> {
                if(hr.code != RetCode.OK) {
                    LOG.error("Fail to register primary.{} into bios,result:{}", si.name, hr.brief());
                    return CompletableFuture.completedFuture(hr);
                }
                
                if(dbDefines.isEmpty()) {
                    return CompletableFuture.completedFuture(hr);
                }
                return si.initWebDb(ci.id, dbDefines);
             });
        }
    }

    DefaultServiceWatcher DefaultWatcher = new DefaultServiceWatcher();
}