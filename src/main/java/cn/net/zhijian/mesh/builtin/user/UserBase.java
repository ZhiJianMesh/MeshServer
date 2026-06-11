package cn.net.zhijian.mesh.builtin.user;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.ConfigClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.mesh.frm.tokenworker.CodebookTokenWorker;
import cn.net.zhijian.mesh.frm.process.RDBProcessor;
import cn.net.zhijian.util.FifoCache;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

public abstract class UserBase extends RDBProcessor implements IOAuth {
    private static final Logger LOG = LogUtil.getInstance();
    
    protected static final long USER_TOKEN_EXPIRES_IN = 366L * 86400L * 1000L; //366 days
    protected static final String PARA_TOKENHASH = "tokenHash";
    protected static final String PARA_UID = "uid";
    protected static final String RESP_EXPIRESAT = "expiresAt";
    private static final String CFG_CODEBOOKS = "codebooks";

    private static final Map<Integer, ITokenWorker> tokenWorkers = new ConcurrentHashMap<>();
   
    /**
     * hashCode(cid,userId@service)->token string
     * 原先使用整个token的hash值，会导致token过期或重新登录后，缓存不能及时清除
     * 从token库的token表加载，logout与refreshtoken时会改变。
     * 每个token的userid占8个字节，token本身需要约200字节，按1:4计算，
     * 一个用户需要1k内存，1万活跃用户需要10M内存。
     * 为了减少内存消耗，在调用方将用户分组，同一分组的用户只发到一个实例上
     */
    protected static final FifoCache<Long, AccessToken> UserTokenCache = new FifoCache<>(
            300, //300秒过期
            10000); //单实例保存1万个活跃token
    
    public UserBase(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        return super.parse(url, cfg, request);
    }

    @Override
    public void destroy() {
        super.destroy();
        UserTokenCache.clear();
    }

    /**
     * 用户tokenworker没有把cid作为签名的一部分。
     * 比如有两个公司A、B，A如果没有获得B的token，
     * 即使知道B的cid，也不能用A的token通过B公司的验证，
     * 因为生成token的密码本不是一个
     * @param cid 公司id，用于区分不同公司的tokenworker
     * @param si 发起调用的服务信息
     * @param cloud 是否生成company token，私有云用户访问公有云服务时使用
     * @return tokenworker token工人
     */
    protected static CompletableFuture<ITokenWorker> getTokenWorker(int cid, ServiceInfo si, boolean cloud) {
        //在私有云环境，且需要访问根环境服务的情况，才需要company token
        //在根环境的公司级服务，访问时必须用user token；
        //个人服务都部署在根环境，必须用uniuser token访问；
        if(cloud && PartitionConfig.instance().isPrivate()) {
            return CompletableFuture.completedFuture(CompanyInfo.instance().tokenWorker());
        }

        ITokenWorker tw = tokenWorkers.get(cid);
        if(tw != null) {
            return CompletableFuture.completedFuture(tw);
        }
        return buildFromCodebook(cid, si).thenApplyAsync(tw1 -> {
            if(tw1 != null) {
                tokenWorkers.put(cid, tw1);
            }
            return tw1;
        }, Pool);
    }
    
    /**
     * 用密码本生成token，或验证token
     * @param cid 公司ID
     * @param si 公司信息
     * @return 密码本token工人
     */
    private static CompletableFuture<ITokenWorker> buildFromCodebook(int cid, ServiceInfo si) {
        return ConfigClient.get(cid, si, CFG_CODEBOOKS, false).thenComposeAsync(cipheredCodebooks -> {
            if (!StringUtil.isEmpty(cipheredCodebooks)) {
                LOG.info("Success to get codebooks from config of {}", si.name);
                return decodeCodebook(cid, si, cipheredCodebooks);
            }
            LOG.warn("No codebooks of {} from bios,try to create it", si.name);
            return createCodebook(cid, si);
        }, Pool).whenCompleteAsync((tw, e) -> {
            if(e != null) {
                LOG.error("Fail to parse code book of {} from config", si.name, e);
            }
        }, Pool);
    }
    
    private static CompletableFuture<ITokenWorker> decodeCodebook(int cid, ServiceInfo si, String cipheredCodebooks) {
        return BiosClient.rootDecode(si, cipheredCodebooks).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK || hr.data == null) {
                LOG.error("Fail to decode codebooks of {} from config,result:{}", si.name, hr.brief());
                return createCodebook(cid, si);
            }
            
            String cbs = ValParser.getAsStr(hr.data, BiosClient.PLAIN);
            ITokenWorker tw = parseCodebookToTokenWorker(cbs);
            if(tw != null) {
                LOG.info("Success to parse codebooks of {} from config", si.name);
                return CompletableFuture.completedFuture(tw);
            }
            
            LOG.error("Fail to get codebooks of {} from config, create it", si.name);
            return createCodebook(cid, si);
        }, Pool);
    }
    
    private static ITokenWorker parseCodebookToTokenWorker(String cbs) {
        if(StringUtil.isEmpty(cbs)) {
            return null;
        }
        try {
            List<byte[]> codebooks = CodebookTokenWorker.parseCodebooks(cbs);
            if(codebooks != null && !codebooks.isEmpty()) {
                LOG.info("Success to parse codebooks, total {}", codebooks.size());
                return new CodebookTokenWorker(codebooks);
            }
            LOG.error("Fail to parse codebooks,src:{}", cbs);
        } catch (IOException e) {
            LOG.error("Fail to parse codebooks,src:{}", cbs, e);
        }
        return null;
    }
    
    private static CompletableFuture<ITokenWorker> createCodebook(int cid, ServiceInfo si) {
        String plain = CodebookTokenWorker.generateCodebooks(3);
        return BiosClient.rootEncode(si, plain).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK || hr.data == null) {
                LOG.error("Fail to encode new codebooks in bios `{}`,result:{}", CFG_CODEBOOKS, hr.brief());
                return CompletableFuture.completedFuture(false);
            }
            String cipheredCbs = ValParser.getAsStr(hr.data, BiosClient.CIPHER);
            return ConfigClient.put(cid, si, CFG_CODEBOOKS, cipheredCbs);
        }, Pool).thenApplyAsync(ok -> ok ? parseCodebookToTokenWorker(plain) : null, Pool);
    }
    
    public static long getCacheId(int cid, String token) {
        return StringUtil.longHashCode(cid, AccessToken.getStakeholder(token));
    }
}