package cn.net.zhijian.mesh.bean;

import java.io.File;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IKeystoreHelper;
import cn.net.zhijian.util.AESUtil;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.IUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * 根密钥加解密实现类
 * 在jvm与安卓版本中实现不同，安卓中用系统keystore加密后存储，jvm中明文存储。
 * java中产生一个AES密钥存在keystore文件中，此文件替换非常繁琐，容易导致原有加密的内容解密失败。
 * 本质是一个AES加密的密钥，密钥算法为AES，长16字节，有以下用途：
 * 1）加密oauth、user服务的codebook，
 *   此应用场景根密钥可更换，密钥更换后，服务器重启codebook会自动更换；
 * 2）加密company.cfg中authKeyPair、accesscode，
 *   如果是公司网络，根密钥可以更换，重新登录重新加密存入根环境即可，
 *   根环境没有accesscode，不存在此问题，authKeyPair需要手动生成、手动加密；
 * 3）加密数据的datarootkey使用公司密钥加密存在根环境，与rootkey没关系，
 *      但是根环境与本地环境存入keystore时使用根密钥加密，
 *   所以私钥部分必须用原根密钥解密后再用新根密钥加密，然后再存入。
 *   此步骤需要使用sbin/command.bat rootkey decode|encode实现，并且要改数据库。
 * 综上所述，root.keystore可丢失，但是丢失后恢复比较麻烦。
 * @author flyinmind of csdn.net
 */
public class RootKeystore {
    private static IKeystoreHelper helper;
    private static RootKeystore instance;

    private final SecretKey key;
    
    private RootKeystore(SecretKey key) {
        this.key = key;
    }

    public static RootKeystore instance() {
        return instance;
    }

    public static RootKeystore init() throws Exception {
        SecretKey key;
        File ksFile = configFile();
        if(!ksFile.exists()) {
            key = createKey();
            helper.save(ksFile, key);
        } else {
            key = helper.load(ksFile);
        }
        instance = new RootKeystore(key);
        return instance;
    }

    public static RootKeystore init(byte[] keyArr) throws Exception {
        File ksFile = configFile();
        SecretKey key = helper.toKey(keyArr);
        instance = new RootKeystore(key);
        helper.save(ksFile, key);
        return instance;
    }

    public static RootKeystore parse(String ks, String pwd) throws Exception {
        SecretKey key = decodeKeyStore(ks, pwd);
        return new RootKeystore(key);
    }
    
    public static RootKeystore create() throws Exception {
        SecretKey key = createKey();
        return new RootKeystore(key);
    }

    public static File configFile() {
        return new File(helper.configFile());
    }

    //必须在程序最开始运行时调用
    public static void setHelper(IKeystoreHelper helper) {
        RootKeystore.helper = helper;
    }

    public byte[] getBinaryKey() throws Exception {
        return helper.toBytes(key);
    }

    /**
     * 加密
     * @param secretKey 存在keystore中的密钥
     * @param plain 明文字符串，在函数中会解开为utf8格式
     * @return 返回二进制的base64格式字符串
     * @throws Exception 算法、io等异常
     */
    static byte[] encode(SecretKey secretKey, byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance(helper.transformation(), helper.provider());
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] iv = cipher.getIV();

        byte[] ciphered = cipher.doFinal(plain);
        byte[] encoded = new byte[1 + iv.length + ciphered.length];
        encoded[0] = (byte)iv.length;
        System.arraycopy(iv, 0, encoded, 1, iv.length);
        System.arraycopy(ciphered, 0, encoded, iv.length + 1, ciphered.length);
        return encoded;
    }
    
    /**
     * 加密
     * @param key AES密钥
     * @param plain 明文字符串，在函数中会解开为utf8格式
     * @return 返回二进制的base64格式字符串
     * @throws Exception 算法、io等异常
     */
    static byte[] encode(byte[] key, byte[] plain) throws Exception {
        return encode(helper.toKey(key), plain);
    }

    /**
     * 解密
     * @param secretKey 存在keystore中的密钥
     * @param encoded base64格式的字符串，在函数中会解码为二进制
     * @return 返回utf8格式的字符串
     * @throws Exception 算法、io等异常
     */
    static byte[] decode(SecretKey secretKey, byte[] encoded) throws Exception {
        int ivLen = encoded[0];
        if(ivLen >= encoded.length) {
            throw new ArrayStoreException("too long iv");
        }
        byte[] iv = new byte[ivLen];
        System.arraycopy(encoded, 1, iv, 0, ivLen);
        byte[] ciphered = new byte[encoded.length - 1 - ivLen];
        System.arraycopy(encoded, 1 + ivLen, ciphered, 0, ciphered.length);
        Cipher cipher = Cipher.getInstance(helper.transformation(), helper.provider());
        GCMParameterSpec spec = new GCMParameterSpec(helper.keySize(), iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

        return cipher.doFinal(ciphered);
    }
    
    /**
     * 解密
     * @param key 存在keystore中的密钥
     * @param encoded base64格式的字符串，在函数中会解码为二进制
     * @return 返回utf8格式的字符串
     * @throws Exception 算法、io等异常
     */
    static byte[] decode(byte[] key, byte[] encoded) throws Exception {
        return decode(helper.toKey(key), encoded);
    }

    /**
     * 创建系统根密钥，用于加解密codebook、datarootkey等。
     * 实际环境文件应从一个可信根或者加密卡等设备中加载。
     * 比如安卓服务器中使用TEE区实现可信根密钥。
     * @return 密钥
     * @throws Exception 算法不存在等异常
     */
    public static SecretKey createKey() throws Exception {
        //----生成根秘钥，用于加密其他数据---------
        // 通过指定算法,指定提供者来构造KeyGenerator对象
        KeyGenerator keyGenerator = KeyGenerator.getInstance(helper.algorithm(), helper.provider());
        // 初始化KeyGenerator对象,通过指定大小和随机源的方式产生
        keyGenerator.init(helper.keySize(), SecureRandom.getInstanceStrong());
        return keyGenerator.generateKey();
    }

    public String encodeKeyStore(String pwd) throws Exception {
        byte[] ksArr = getBinaryKey();
        byte[] encodedKs;
        if(!StringUtil.isEmpty(pwd)) {
            byte[] pwdArr = pwd.getBytes(IConst.DEFAULT_CHARSET);
            encodedKs = AESUtil.cbcEncrypt128(ksArr, pwdArr);
        } else {
            encodedKs = ksArr;
        }
        return ByteUtil.bin2base64(encodedKs);
    }

    public static SecretKey decodeKeyStore(String ks, String pwd) throws Exception {
        byte[] ksArr = ByteUtil.base642bin(ks);
        byte[] decodedKs;
        if(!StringUtil.isEmpty(pwd)) {
            byte[] pwdArr = pwd.getBytes(IConst.DEFAULT_CHARSET);
            decodedKs = AESUtil.cbcDecrypt128(ksArr, pwdArr);
        } else {
            decodedKs = ksArr;
        }
        return helper.toKey(decodedKs);
    }

    /**
     * 加密
     * @param secretKey 存在keystore中的密钥
     * @param plain 明文字符串，在函数中会解开为utf8格式
     * @return 返回二进制的base64格式字符串
     * @throws Exception 算法、io等异常
     */
    public static String encode(SecretKey secretKey, String plain) throws Exception {
        byte[] cipheredBytes = encode(secretKey, plain.getBytes(IUtil.DEFAULT_CHARSET));
        return ByteUtil.bin2base64(cipheredBytes);
    }

    /**
     * 解密
     * @param secretKey 存在keystore中的密钥
     * @param encoded base64格式的字符串，在函数中会解码为二进制
     * @return 返回utf8格式的字符串
     * @throws Exception 算法、io等异常
     */
    static String decode(SecretKey secretKey, String encoded) throws Exception {
        byte[] encodedBytes = ByteUtil.base642bin(encoded);
        byte[] plainBytes = decode(secretKey, encodedBytes);
        return new String(plainBytes, IConst.DEFAULT_CHARSET);
    }

    /**
     * 解密
     * @param secretKey AES密钥
     * @param encoded base64格式的字符串，在函数中会解码为二进制
     * @return 返回utf8格式的字符串
     * @throws Exception 算法、io等异常
     */
    static String decode(byte[] secretKey, String encoded) throws Exception {
        byte[] encodedBytes = ByteUtil.base642bin(encoded);
        byte[] plainBytes = decode(secretKey, encodedBytes);
        return new String(plainBytes, IConst.DEFAULT_CHARSET);
    }

    public String decode(String cipher) throws Exception {
        return decode(key, cipher);
    }

    public String encode(String plain) throws Exception {
        return encode(key, plain);
    }

    public byte[] encode(byte[] plain) throws Exception {
        return encode(key, plain);
    }

    public byte[] decode(byte[] cipher) throws Exception {
        return decode(key, cipher);
    }
}
