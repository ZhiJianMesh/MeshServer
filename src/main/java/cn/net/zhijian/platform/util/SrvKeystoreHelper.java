package cn.net.zhijian.platform.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.Provider;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.frm.intf.IKeystoreHelper;
import cn.net.zhijian.util.BouncyBase;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.LogUtil;

/**
 * keystore工具类
 * 因为没有根密钥，keystore密码必须写在程序中，不能提升安全性，
 * 并且keystore文件在跨系统时会出现很多兼容性问题，所以弃用，直接用二进制文件记录
 * 在启动时如果没有会自动创建
 * jvm中明文存储，如果有条件，建议使用TEE/REE加密存储；
 * android中使用系统keystore加密后存储，系统keystore使用了android自带的TEE/REE；
 * 两种环境中，存入bios中的都是明文，
 * 因为android中，一旦集群，每个设备上的keystore是不能互通的
 * @author flyinmind of csdn.net
 *
 */
public class SrvKeystoreHelper implements IKeystoreHelper {
    private static final Logger LOG = LogUtil.getInstance();
    //触发BouncyBase加载BouncyCastleProvider，所以尽管可以直接引用，还是在这里赋值一次
    private static final Provider PROVIDER = BouncyBase.PROVIDER;
    private static final String KEYSTORE_FILE = "root.keystore";

    private static final int KEY_SIZE = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private final String confPath;

    public SrvKeystoreHelper(String confPath) {
        this.confPath = confPath;
        LOG.debug("Key store file:{}", configFile());
    }

    @Override
    public void save(File file, SecretKey ks) throws Exception {
        /* 最好使用硬件信任根，在服务器上无此条件，只能产生一个信任根文件
         * 在安卓中，使用keystore实现（基于TEE），它更加安全。
         * root.keystore用于加密oauth、user服务的codebook、数据密钥，
         * 即使丢失此密钥，也不会影响使用，因为会重新创建密钥及codebook。
         * 但是，对于在根环境中运行的公司，它们的数据根密钥是用它加密的，
         * 所以，一旦至简网格的root.keystore丢失，则使用数据根密钥加密的数据字段无法解开。
         */
        // ROOTPWD密钥库密码
        try(FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(toBytes(ks));
        }
    }

    @Override
    public SecretKey load(File file) throws Exception {
        try(FileInputStream fis = new FileInputStream(file)) {
            byte[] keyArr = fis.readAllBytes();
            return toKey(keyArr);
        }
    }
    
    @Override
    public Provider provider() {
        return PROVIDER;
    }

    @Override
    public int keySize() {
        return KEY_SIZE;
    }

    @Override
    public String transformation() {
        return TRANSFORMATION;
    }

    @Override
    public String algorithm() {
        return KEY_ALGORITHM;
    }

    @Override
    public String configFile() {
        return FileUtil.addPath(confPath, KEYSTORE_FILE);
    }

    @Override
    public SecretKey toKey(byte[] keyArr) {
        return new SecretKeySpec(keyArr, algorithm());
    }

    @Override
    public byte[] toBytes(SecretKey key) {
        return key.getEncoded();
    }
}