package cn.net.zhijian.mesh.builtin.bios;

import java.security.PublicKey;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.DeferrableEle;
import cn.net.zhijian.mesh.dbworker.TreeDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsTokenChecker;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.tokenworker.EccTokenWorker;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * 判断调用bios的服务token是否合法，类似APP-*+APP-OM。 
 * 1）如果是运维平台的调用，使用运维平台的公钥验签；
 * 2）如果是其他平台的调用，需要从bios metadb中获取服务公钥验签，获得的公钥不一定及时； 
 * 3）bios是公共服务，不校验partition是否与本机一致。
 * 
 * @author flyinmind of csdn.net
 * <p></p>
 * service
 *  |_service1
 *      |_publicKey
 *      |_caller
 *        |_service2->features
 *        |_service3->features
 *      |_dbs(一种特殊服务)
 *         |_db1->tabledef
 *         |_db2->tabledef
 */
final class BiosTokenChecker extends AbsTokenChecker {
    private static final Logger LOG = LogUtil.getInstance();

    private static final String SERVICE_DIR = "/service";
    private static final String SERVICE_KEY = "key";

    /**
     * 因为服务不会太多，几百级别，且公钥占用内存很少，每个不到1K，
     * 变化不会很频繁，所以在每个bios实例中都缓存了所有服务的公钥
     */
    private static final Map<Integer, AppTokenChecker> AppTokenCheckers = new ConcurrentHashMap<>();

    public BiosTokenChecker(String name, ServiceInfo si) {
        super(name);
        // 将自身的tokenwork放入缓存，永不过期，自己调用自己时，无需查询
        AppTokenCheckers.put(SERVICE_BIOS.hashCode(), new AppTokenChecker(si.tokenWorker, 0));
    }

    @Override
    public CompletableFuture<AccessToken> check(AbsServerRequest req, String token) {
        long id = AccessToken.hashCode(token);
        AccessToken cacheToken = CachedTokens.get(id); // 有缓存则用缓存的
        if (cacheToken != null && !cacheToken.needUpdate()) {
            return CompletableFuture.completedFuture(cacheToken);
        }

        ServiceInfo si = req.serviceInfo();
        int signType = AccessToken.getSignType(token);
        if(signType != SIGNTYPE_APPKEY && signType != SIGNTYPE_OMKEY) {
            LOG.error("Only SIGNTYPE_APPKEY,SIGNTYPE_OMKEY allowed,cur:{},in `{}`,token:{}",
                    SIGNTYPE_NAMES[signType], req.uri, token);
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<AppTokenChecker> cf = null;
        String caller = AccessToken.getCaller(token);
        //caller是BIOS时，一定能查到，因为在构造函数中已添加
        AppTokenChecker appPubKey = AppTokenCheckers.get(caller.hashCode());
        boolean needUpdate = false;
        if(appPubKey != null) {
            /*
             * 不判断过期，因为极端情况下，过期了也可以延期使用；定期提前更新，只有一个线程执行，
             * 但不做定期清理过期的应用信息的工作，因为应用信息不多，且极少删除。
             * 过期的公钥，即使需要更新，也仍然继续使用，因为更新都是提前操作的
             */
            needUpdate = appPubKey.needUpdate();
            cf = CompletableFuture.completedFuture(appPubKey);
        }

        //与APP不同之处在于bios可以直接从数据库查询获得服务密钥对
        if(needUpdate || cf == null) {
            CompletableFuture<AppTokenChecker> cf1 = CompletableFuture.supplyAsync(()-> {
                //查询本机的metadb，不是最及时的版本，因为公私钥信息不常变化，所以关系不大
                TreeDBWorker db = (TreeDBWorker)si.getLocalDBWorker(IDBConst.TREEDB, BiosMetaHandler.DB);
                if(db == null) {
                    LOG.error("Fail to get treedb {}.{}", SERVICE_BIOS, BiosMetaHandler.DB);
                    return null;
                }

                String s = db.getValue(SERVICE_DIR + '/' + caller + '/' + SERVICE_KEY);
                if(StringUtil.isEmpty(s)) {
                    LOG.warn("{}/{}/{} not exist", SERVICE_DIR, caller, SERVICE_KEY);
                    return null;
                }

                EccKeyPair kp;
                try {
                    kp = EccKeyPair.parse(s);
                } catch (Exception e) {
                    LOG.warn("Fail to parse public key of {}", caller);
                    return null;
                }
                AppTokenChecker apk = new AppTokenChecker(kp.ver, kp.pub);
                AppTokenCheckers.put(caller.hashCode(), apk);
                return apk;
            }, Pool);
            
            //更新请求仍然发出，但是只要有缓存，则一定使用缓存的，无论更新结果如何
             if(cf == null) {
                cf = cf1;
            }
        }

        return cf.thenComposeAsync(apk -> {
            if(apk == null) {
                LOG.error("Fail to get public key of service `{}` ", caller);
                if(appPubKey != null) {
                    appPubKey.resetUpdate(); //下个请求需要再次更新
                }
                return CompletableFuture.completedFuture(null);
            }

            AccessToken at = AccessToken.parse(token, apk.tokenWorker);
            if(at == null) {
                LOG.error("Fail to check sign with appPublicKey of `{}`,{}", caller, apk.tokenWorker.getPublicKey());
                return CompletableFuture.completedFuture(null);
            }
            CachedTokens.put(id, at);
            return CompletableFuture.completedFuture(at);
        }, Pool);
    }

    private static class AppTokenChecker extends DeferrableEle {
        public final EccTokenWorker tokenWorker;

        public AppTokenChecker(int ver, PublicKey publicKey, int expiresIn) {
            super(expiresIn);
            this.tokenWorker = new EccTokenWorker(ver, null, publicKey, IOAuth.SIGNTYPE_APPKEY);
        }

        public AppTokenChecker(EccTokenWorker tokenWorker, int expiresIn) {
            super(expiresIn);
            this.tokenWorker = tokenWorker;
        }

        public AppTokenChecker(int ver, PublicKey publicKey) {
            this(ver, publicKey, 3 * DEFAULT_EXPIRES_IN);
        }
    }
}