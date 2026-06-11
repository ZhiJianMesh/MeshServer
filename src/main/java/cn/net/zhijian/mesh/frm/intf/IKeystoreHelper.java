package cn.net.zhijian.mesh.frm.intf;

import java.io.File;
import java.security.Provider;

import javax.crypto.SecretKey;

public interface IKeystoreHelper {
    /**
     * 保存根密钥，jvm中直接保存到文件，
     * 如果有TEE/REE根密钥，建议save/load使用根密钥加解密
     * android中keystore加密后保存到文件中
     * @throws Exception 任何异常
     */
    void save(File file, SecretKey key) throws Exception;
    SecretKey load(File file) throws Exception;
    SecretKey toKey(byte[] keyArr);
    byte[] toBytes(SecretKey key);
    int keySize(); //密钥长度，通常为128位
    String transformation(); //转换名称
    String algorithm(); //加密算法
    String configFile(); //存储文件名
    Provider provider(); //产生key、加解密的provider
}
