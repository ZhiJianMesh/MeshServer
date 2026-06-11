package cn.net.zhijian.mesh.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 访问keystore服务的客户端
 * 配置字段加解密时，只需要指定密钥名称即可，无需事先创建密钥。
 * 加密字段存储格式：base64(ver) + @ [+ algorithm] + cipher
 * ver从1开始，如果未加密，则ver为'0'，且无algorithm位
 * <p>
 * 此类支持多个公司，因为计算DataKeys的主键时，使用了公司ID
 * @author flyinmind of csdn.net
 *
 */
public class KeyStoreClient extends ServiceClient implements IOAuth, IThreadPool {
    private static final Logger LOG = LogUtil.getInstance();
    
    public static final long DEFAULT_INTERVAL = 86400L * 1000 * 366; //one year
    public static final long MIN_INTERVAL = 86400000L; //one day
    
    private static final char ZERO_CHAR = ByteUtil.getBase64CharByVal(0);
    private static final String ZERO_STR = "" + ZERO_CHAR;
    private static final char SEPARATOR = '@';
    private static final char ALGORITHM_AES = '0'; //AES128，当前固定，先占位，方便以后扩展

    /**
     * 数据秘钥，用于对数据进行加解密。
     * 密钥经过根密钥解密后缓存到DataKeys中，
     * 密钥需要定期更新，更新时，同名密钥只增加版本，
     * 加密时，总是使用最新版本密钥加密，解密时，选择对应版本解密，
     * 所以，所有版本的密钥都需要保存
     */
    private static final Map<Integer, DataKey> DataKeys = new ConcurrentHashMap<>();

    /**
     * 在密码箱中创建密钥，任何应用都可以创建密钥
     * @param cid 公司id
     * @param caller 调用的服务信息
     * @param keyName 密码箱中密钥的名称
     * @param pwd 用户密码，用于加密密码箱中的密码
     * @return 创建结果
     */
    private static CompletableFuture<HandleResult> createKey(int cid, ServiceInfo caller, String keyName, String pwd) {
        ServiceReqBuilder req = new ServiceReqBuilder(caller, SERVICE_KEYSTORE)
                .url("/put")
                .cid(cid)
                .put("pwd", pwd)
                .put("name", keyName)
                .traceId(caller.name);
        return putPrivate(req);
    }
    
    private static CompletableFuture<DataKey> createKey(int keyId, int cid, ServiceInfo caller, String keyName) {
        byte[] key = ByteUtil.uuid();
        return createKey(cid, caller, keyName, ByteUtil.bin2base64(key)).thenComposeAsync(hr -> {
            DataKey dk = DataKeys.get(keyId);
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to create key {}.{}.{},result:{}", cid, caller.name, keyName, hr.brief());
                return CompletableFuture.completedFuture(dk);
            }

            if(dk == null) {
                dk = new DataKey();
                DataKeys.put(keyId, dk);
            }
            int ver = ValParser.getAsInt(hr.data, "ver");
            dk.addKey(ver, System.currentTimeMillis(), key);
            return CompletableFuture.completedFuture(dk);
        }, Pool);
    }
    
    /**
     * 获得密钥
     * @param cid 公司id
     * @param caller 调用方信息
     * @param keyName 密钥名称
     * @param interval 密钥有效期
     * @return 删除结果
     */
    private static CompletableFuture<DataKey> getKey(int cid, ServiceInfo caller, String keyName, long interval) {
        int hashCode = StringUtil.concatHashCode(cid, caller.name, keyName);
        int keyId = ValParser.absInt(hashCode); //缓存id，非密钥id
        DataKey dk = DataKeys.get(keyId);
        if(dk != null) {
            if(dk.expired(interval)) {
                return createKey(keyId, cid, caller, keyName);
            }
            return CompletableFuture.completedFuture(dk);
        }

        ServiceReqBuilder req = new ServiceReqBuilder(caller, SERVICE_KEYSTORE)
                .url("/get").appendPara("name", keyName)
                .cid(cid)
                .traceId(caller.name + '_' + cid);
        return getPrivate(req).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                return createKey(keyId, cid, caller, keyName);
            }
            
            List<Object> list = ValParser.getAsList(hr.data, "keys");
            DataKey dataKey = DataKey.parse(list);
            if(dataKey == null || dataKey.expired(interval)) {
                return createKey(keyId, cid, caller, keyName);
            }
            DataKeys.put(keyId, dataKey);
            return CompletableFuture.completedFuture(dataKey);
        }, Pool);
    }

    /**
     * 用密码箱加密数据
     * @param cid 公司id
     * @param caller 调用方信息
     * @param keyName 密钥名称
     * @param interval 密钥有效期
     * @param plain 待加密的数据
     * @return 加密结果，ver + @ + 内容，ver从1开始，未加密时，ver为'0'
     */
    public static CompletableFuture<String> encode(int cid, ServiceInfo caller,
            String keyName, long interval, String plain) {
        return getKey(cid, caller, keyName, interval).thenApplyAsync(dk -> {
            byte[] key;
            if(dk == null || (key = dk.getKey()) == null) {
                return ZERO_STR + SEPARATOR + plain; //无法加密
            }
            //始终使用最新版本加密
            byte[] cipher = SecureUtil.encode(plain.getBytes(DEFAULT_CHARSET), key);
            return ByteUtil.int2Base64(dk.curVer()) + SEPARATOR + ALGORITHM_AES + ByteUtil.bin2base64(cipher);
        }, Pool);
    }
    
    /**
     * 三重秘钥加密
     * 第一重：元密钥，公有云使用根密钥加密数据根密钥，私有云使用登录密钥加密
     * 第二重：数据根秘钥，使用元密钥加密后存在至简网格的keystore中，防止丢失，导致加密数据都无法解开，
     *        从至简网格恢复到本地companyinfo中（company.cfg）；
     * 第三重：数据秘钥，记录在keystore服务中，通过根秘钥解密；
     * encode与decode根据cid、service、keyName以及密钥版本四个元素决定数据密钥，
     * 获得数据密钥后使用数据根密钥解密数据密钥，然后才能解密数据
     * @param req 请求信息
     * @param keyName 密钥名称，不必事先创建，如果没有会自动创建，密钥都是16字节随机内容
     * @param interval 密钥有效期
     * @param plain 明文字符串
     * @return 加密后的字符串，加密失败时返回`0@...`
     */
    public static String encode(AbsServerRequest req, String keyName,
            long interval, String plain) {
        try {
            return encode(req.cid(), req.serviceInfo(), keyName, interval, plain)
                    .get(5000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.warn("Fail to encode data", e);
            return ZERO_STR + SEPARATOR + plain;
        }
    }

    /**
     * 用密码箱解密数据
     * @param cid 公司id
     * @param caller 调用方信息
     * @param keyName 密钥名称
     * @param cipher 待解密的数据，ver + @ + 内容
     * @return 解密结果
     */
    public static CompletableFuture<String> decode(int cid, ServiceInfo caller, String keyName, String cipher) {
        int pos = cipher.indexOf(SEPARATOR);
        if(pos < 1) { //格式不正确，直接返回原文
            return CompletableFuture.completedFuture(cipher);
        }
        
        if(pos == 1 && cipher.charAt(0) == ZERO_CHAR) { //未加密,0@xxxx
            return CompletableFuture.completedFuture(cipher.substring(2));
        }
        
        return getKey(cid, caller, keyName, Long.MAX_VALUE).thenApplyAsync(dk -> {
            int ver = ByteUtil.base642Int(cipher.substring(0, pos));
            String s = cipher.substring(pos + 2); //ver+@+alg(1)+cipher
            byte[] key;
            if(dk == null || (key = dk.getKey(ver)) == null) {
                LOG.warn("Key ver not exists `{}.{}.{}.{}`", cid, caller.name, keyName, ver);
                return s; //无法解密，直接返回密文
            }

            byte[] plain = SecureUtil.decode(ByteUtil.base642bin(s), key);
            if(plain == null) {
                LOG.warn("Fail to decode {}", s);
                return s;
            }
            return new String(plain, DEFAULT_CHARSET);
        }, Pool);
    }
    
    /**
     * 解密
     * @param req 请求信息
     * @param keyName 密钥名称，不必事先创建，如果没有会自动创建，密钥都是16字节随机内容
     * @param cipher 密文
     * @return 解密后的字符串，解密失败时，原样返回
     */
    public static String decode(AbsServerRequest req, String keyName, String cipher) {
        try {
            return decode(req.cid(), req.serviceInfo(), keyName, cipher)
                    .get(5000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.warn("Fail to decode data", e);
            return cipher; //原样返回
        }
    }
    
    private static class DataKey {
        int latest = 0;
        long createAt = 0;
        final Map<Integer, byte[]> keyMap = new HashMap<>();//ver->key

        void addKey(int ver, long createAt, byte[] key) {
            if(this.latest < ver) {
                this.latest = ver;
            }
            if(this.createAt < createAt) {
                this.createAt = createAt;
            }
            this.keyMap.put(ver, key);
        }
        
        byte[] getKey(int ver) {
            return keyMap.get(ver);
        }
        
        byte[] getKey() {
            return keyMap.get(latest);
        }
        
        int curVer() {
            return latest;
        }
        
        boolean expired(long interval) {//change password each year by default
            return System.currentTimeMillis() - createAt > interval;
        }
        
        static DataKey parse(List<Object> list) {
            if(list == null || list.isEmpty()) {
                return null;
            }
            DataKey dk = new DataKey();

            for(Object o : list) {
                Map<String, Object> cfg = ValParser.parseObject(o);
                int ver = ValParser.getAsInt(cfg, "ver");
                long createAt = ValParser.getAsLong(cfg, "createAt");
                String sPwd = ValParser.getAsStr(cfg, "pwd");
                byte[] pwd = ByteUtil.base642bin(sPwd);
                dk.addKey(ver, createAt, pwd);
            }
            return dk.keyMap.isEmpty() ? null : dk;
        }
    }
}
