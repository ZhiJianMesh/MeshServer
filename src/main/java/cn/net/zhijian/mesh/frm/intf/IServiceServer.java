package cn.net.zhijian.mesh.frm.intf;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.abs.AbsFileMethod;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.method.ApiMethod;
import cn.net.zhijian.mesh.frm.service.BuiltinInitializer;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;

public interface IServiceServer {
    String INIT_METHOD_HEAD = "__";
    
    long startupAt = System.currentTimeMillis(); //启动时间


    /**
     * 增加文件方法
     * @param url url
     * @param fm 文件方法
     */
    void addFile(String url, AbsFileMethod fm);

    /**
     * 增加接口方法
     * @param am 接口方法
     */
    void addApi(ApiMethod am);
    
    /**
     * 判断一个api是否存在
     * @param url api的完整url
     * @return api定义
     */
    ApiMethod getApi(String url);
    
    /**
     * 判断一个file是否存在
     * @param url api的完整url
     * @return file定义
     */
    AbsFileMethod getFile(String url);
    
    /**
     * 执行文件请求
     * @param req 请求体
     */
    void fileExecute(AbsServerRequest req);
    
    /**
     * 执行接口请求
     * @param req 请求体
     */
    void apiExecute(AbsServerRequest req);
    
    /**
     * 运行的根路径
     * @return 路径
     */
    String homeDir();
    
    /**
     * 安装服务，并将服务注册到系统中，使得它可以被使用
     * @param service 服务名
     * @param pwd 公司密码或om密码，有新服务，重启时需要pwd获取服务密钥对
     * @return 异步结果
     */
    CompletableFuture<HandleResult> install(String service, String pwd);
    
    /**
     * 卸载服务，并从系统中注销掉该服务
     * @param service 服务名称
     * @param omPwd om密码
     */
    CompletableFuture<HandleResult> unInstall(String service, String omPwd);

    /**
     * 升级服务，并将升级后的服务重新注册到系统中
     * @param service 服务名
     * @param omPwd om密码
     * @return 异步结果
     */
    CompletableFuture<HandleResult> update(String service, String omPwd);

    /**
     * 销毁
     */
    void destroy();
    
    /**
     * 获得已安装服务的信息
     * @param service 服务名称
     * @return 服务信息
     */
    ServiceInfo getService(String service);
    
    /**
     * 列举某个服务的所有接口
     * @param service 服务名
     * @return 接口列表
     */
    List<Map<String, String>> apis(String service);
    
    /**
     * 列举某个服务的所有文件
     * @param service 服务名
     * @return 文件列表
     */
    List<String> files(String service);
    
    /**
     * 列举已安装的服务
     * @return 服务列表
     */
    Map<String, ServiceInfo> services();

    void addService(ServiceInfo si);
    
    /**
     * 加载服务配置
     * @param pwd 公司密码或om密码
     * @return 成功则返回true
     */
    boolean startServices(String pwd);
    
    /**
     * 返回指定服务的访问统计信息
     * @param cid 公司ID
     * @param service 服务名
     * @param from 开始时间（包括），UTC小时
     * @param to 结束时间，UTC小时
     * @return 数据列表，at,api,exc,fail,file
     */
    List<Object[]> visitStats(int cid, String service, int from, int to);
    
    abstract class AbsServiceServer implements IServiceServer, IConst {
        private static final Logger LOG = LogUtil.getInstance();
        private static final String SERVICES_KEYSTORE = "services.keystore";
        protected final String workDir;
        protected final Map<String, ServiceInfo> services = new HashMap<>();

        public AbsServiceServer(String workDir) throws MeshException {
            this.workDir = workDir;
            loadServices(); //ServiceInfo在服务接口加载前加载
        }

        private void loadServices() throws MeshException {
            services.clear();
            Map<String, String> kps = readKeyPairs();

            ServiceInfo backend = ServiceClient.backendService(); //需要保证backend初始化
            addService(backend);
            services.put(backend.name, backend);
            if(PartitionConfig.instance().isPrivate()) {
                LOG.info("Create private compatible company/httpdns service");
                //私有云环境每个实例都有company、httpdns兼容服务
                //放在后面会导致两个虚拟服务没有认证密钥对
                BuiltinInitializer.createCompatibleServices(this);
            }
            
            for(ServiceInfo si : services.values()) {
                EccKeyPair ekp = getServiceKeypair(si.name, kps);
                if(ekp != null) {
                    si.tokenWorker.setKey(ekp); //设置内置服务的认证密钥
                }
            }

            List<String> serviceList = ServiceInfo.listServices();
            for(String serviceHome : serviceList) {
                int pos = serviceHome.lastIndexOf(File.separatorChar);
                String serviceName = serviceHome.substring(pos + 1);
                ServiceInfo si = services.get(serviceName);
                if(si == null) { //内置的服务，即使本地有相应的服务配置，也无需再次加载
                    EccKeyPair ekp = getServiceKeypair(serviceName, kps);
                    si = ServiceInfo.parse(serviceHome, serviceName, ekp, true);
                    if(si == null) {//服务加载失败，不影响服务器启动
                        LOG.error("Fail to parse service {}", serviceName);
                        continue;
                    }
                }

                services.put(si.name, si);
            }
        }
        
        private EccKeyPair getServiceKeypair(String service, Map<String, String> kps) throws MeshException {
            String sKp = kps.get(service);
            if(!StringUtil.isEmpty(sKp)) {
                try {
                    return EccKeyPair.parse(sKp);
                } catch (Exception e) {
                    LOG.error("Fail to parse {}'s keypair", service, e);
                    throw new MeshException("Invalid service key pair of " + service, e);
                }
            }
            return null;
        }

        private File ksFile() {
            return new File(FileUtil.addPath(workDir, SYS_CONF_DIR, SERVICES_KEYSTORE));
        }

        /**
         * 从services.keystore中读取服务的认证密钥对，
         * 如果service.cfg中配置了，就使用配置中的，否则使用preference中的。
         * @return 服务键值对
         */
        protected synchronized Map<String, String> readKeyPairs() {
            File ksf = ksFile();
            if(!ksf.exists()) {
                LOG.warn("{} not exists", ksf);
                return new HashMap<>();
            }
            LOG.debug("Reading {}", ksf);
            Map<String, Object> map = null;
            try {
                map = JsonUtil.jsonFileToMap(ksf, true);
            } catch(Exception e) {
                LOG.error("Invalid service keypair file '{}'", ksf, e);
            }
            if(map == null) {
                LOG.warn("No service key exists in {}", ksf);
                return new HashMap<>();
            }
            Map<String, String> strMap = new HashMap<>();
            map.forEach((k, v) -> strMap.put(k, v.toString()));
            
            LOG.debug("Total {} services key exist in {}", map.size(), ksf);
            return strMap;
        }

        protected void saveKeyPairs(Map<String, String> kps) {
            String s = JsonUtil.objToPrettyJson(kps);
            FileUtil.writeFile(ksFile(), s, IConst.DEFAULT_CHARSET);
        }
        
        @Override
        public Map<String, ServiceInfo> services() {
            synchronized(services) {
                return Collections.unmodifiableMap(services);
            }
        }

        @Override
        public void addService(ServiceInfo si) {
            synchronized(services) {
                services.put(si.name, si);
            }
        }
        
        @Override
        public ServiceInfo getService(String serviceName) {
            synchronized(services) {
                return services.get(serviceName);
            }
        }
        
        
        @Override
        public String homeDir() {
            return workDir;
        }
    }
}
