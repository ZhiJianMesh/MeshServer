package cn.net.zhijian.mesh.builtin.keystore;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.DBClient;
import cn.net.zhijian.mesh.client.DBClient.DBReqBuilder;
import cn.net.zhijian.mesh.client.DBClient.SQLAction;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.mesh.frm.process.RDBProcessor;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.Ecc.StrKP;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;
/**
 * 使用根数据密钥解开数据密钥
 * 此类实现支持多个公司同时使用，keypair的主键是公司ID
 * 如果不是根公司，keypair中无数据，使用本地的datarootkeypair，
 * 此密钥对是用公司密钥加密后存在云侧的keystore中，
 * 每次服务器上执行公司登录时会取下来存在本地
 */
public class KeyStoreBase extends RDBProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    /* 数据根密钥缓存
     * 本地公司的根密钥在启动时使用addKeyPair存入了缓存，所以不用保存到数据库。
     * 如果公司在公有云，则需要调用/company/putcdr将密钥存入keystore表，
     * 运行时从表中查出来，存入缓存。
     * 当公司从公有云迁移到私有云时，根密钥需要使用公司密码加密后再存入keystore表，
     * 此时公司的数据加解密将不依赖公有云keystore服务
     */
    private static final Map<Integer, EccKeyPair> DataRootKeys = new HashMap<>();
    
    public KeyStoreBase(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    static void addKeyPair(int cid, EccKeyPair kp) {
        DataRootKeys.put(cid, kp);
    }
    
    static void removeKeyPair(int cid) {
        DataRootKeys.remove(cid);
    }
    
    /**
     * 存在keystore中的数据根密钥分两种加密情况，
     * CDR:公司服务运行在公有云上，使用系统的根密钥加密的密钥对；
     * PDR:公司服务运行在私有云里，使用公司登录密码加密，云上keystore只起备份作用，
     *     私有云每次登录时，都会调用putIfAbsent，从运上获取备份的根密钥
     * @param cid 公司ID
     * @param si 服务信息
     * @return 获得数据根密钥
     */
    static CompletableFuture<EccKeyPair> getDataRootKey(int cid, ServiceInfo si) {
        EccKeyPair kp = DataRootKeys.get(cid);
        if(kp != null) {
            /*
             * 不是根公司的情况，在启动时，已在CompanyInfo.setDataRootKeyPair
             * 中调用addKeyPair存在keypairs中了，
             * 所以不会有查询数据库的操作，所以，私有云的情况，数据库中无keypair，
             * data root keypair经根密钥加密后存在preference中，
             * 在安卓服务器中公司根密钥由安卓tee实现，java服务器中启动时生成了一个根密钥文件
             */
            return CompletableFuture.completedFuture(kp);
        }

        return si.getDbNo(cid).thenComposeAsync(dbNo -> {
            if(dbNo < 0) {
                LOG.warn("Invalid db no {} of company({})", dbNo, cid);
                return CompletableFuture.completedFuture(null);
            }
            return getDataRootKey(si, cid, dbNo);
        }, Pool);
    }
    
    private static CompletableFuture<EccKeyPair> getDataRootKey(ServiceInfo caller, int cid, int dbNo) {
        //无论公司服务运行在公司环境还是根环境，类型都是CDR
        //并且都用当前primary公司的根密钥加密
        //如果是根环境记录的公司环境的datarootkey，用公司密码加密
        DBReqBuilder req = new DBReqBuilder(caller, "keystore", dbNo)
                .readSlave(true);
        req.traceId(caller.name + "_getkp")
           .put(IDBConst.DB_REQ_TIME, System.currentTimeMillis())
           .cid(cid);
    
        SQLAction[] acts = new SQLAction[] {
            SQLAction.create("get_key_pair")
            .merge(true)
            .metas(IDBConst.META_EACH)
            .multi(false)
            //PDR私钥，用公有云根密钥解不开，只能用公司的登录密钥解开
            .sql("select kp from keypair where cid=" + cid
                 //不是云上公司，根密钥是用公司密钥加密的，无法解开
                 + " and type='" + CompanyInfo.DATAROOT_INCLOUD + "'") 
        };

        return DBClient.rdbRequest(req, acts).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                return CompletableFuture.completedFuture(null);
            }

            String sKp = ValParser.getAsStr(hr.data, "kp");
            StrKP sk;
            try {
                sk = StrKP.parse(sKp);
            } catch (InvalidKeySpecException e) {
                return CompletableFuture.completedFuture(null);
            }

            //根环境中运行的公司的私钥采用根环境的根密钥加密，公钥不加密
            return BiosClient.rootDecode(caller, sk.prv).thenComposeAsync(decodeHr -> {
                if(decodeHr.code != RetCode.OK) {
                    LOG.error("Fail to root decode {}, result:{}", sk.prv, decodeHr.brief());
                    return CompletableFuture.completedFuture(null);
                }
                Ecc ecc = Ecc.instance(sk.ver);
                try {
                    PublicKey pubKey = ecc.publicKey(ByteUtil.stdBase64Decode(sk.pub));
                    String plainPriKey = ValParser.getAsStr(decodeHr.data, BiosClient.PLAIN);
                    PrivateKey priKey = ecc.str2PrivateKey(plainPriKey);
                    EccKeyPair newKp = new EccKeyPair(priKey, pubKey, sk.ver);
                    DataRootKeys.put(cid, newKp);
                    return CompletableFuture.completedFuture(newKp);
                } catch(Exception e) {
                    LOG.error("Fail to root decode {}", sk.prv, e);
                    return CompletableFuture.completedFuture(null);
                }
            }, Pool);
        }, Pool);
    }
    
    /**
     * 使用公司根密钥加密数据根密钥
     * @param si 调用服务信息
     * @param dataRootkey 数据根密钥对
     * @return 加密后的数据根密钥对
     */
    static CompletableFuture<String> encodeDataRootkey(ServiceInfo si, String dataRootkey) {
        StrKP sk;
        try {
            sk = StrKP.parse(dataRootkey);
        } catch (InvalidKeySpecException e) { //impossible
            return CompletableFuture.completedFuture(null);
        }
        return BiosClient.rootEncode(si, sk.prv).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to root encode {}, result:{}", sk.prv, hr.brief());
                return null;
            }
            String cipherPriKey = ValParser.getAsStr(hr.data, BiosClient.CIPHER);
            return EccKeyPair.toString(cipherPriKey, sk.pub, sk.ver);
        }, Pool);
    }
}
