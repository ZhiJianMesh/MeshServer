package cn.net.zhijian.platform.cmd;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import java.security.Provider;
import java.security.Security;

import cn.net.zhijian.mesh.bean.RootKeystore;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.BouncyBase;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.SecureUtil;

public class RootKey extends AbsCommand {
    public RootKey(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if(args.length < 2) {
            printHelp(help());
            return false;
        }
        //encode|decode content[ path_to_rootkey[ pkcs]]
        String result;
        String cmd = args[0].toLowerCase();
        String content = args[1];
        String confDir = args.length > 2 ? args[2] : configDir;
        if(args.length > 3 && args[3].equalsIgnoreCase("pkcs")) {
            PKCSKeyStore worker = new PKCSKeyStore(confDir);
            if(cmd.equals("encode")) {
                result = worker.encode(content);
            } else {
                result = worker.decode(content);
            }
        } else {
            if(args[0].equals("encode")) {
                result = RootKeystore.instance().encode(content);
            } else {
                result = RootKeystore.instance().decode(content);
            }
        }
        System.out.println(result);
        return true;
    }

    @Override
    public String[] help() {
        return new String[]{
                "encode or decode with root key",
                name + " encode|decode content[ path_to_rootkeystore]"};
    }
    
    /**
     * 兼容老版本PKCS#12的keystore
     * 因为jvm、android兼容性问题，更改成BKS，
     * 然后又出现linux、windows相同的keystore不能互相加解密，
     * 所以最后换成直接将SecretKey存入文件。
     * keystore存储密钥需要提供密码，如果写死在程序中，并不能提升安全性，
     * 所以使用keystore的必要性不高
     */
    public static class PKCSKeyStore {
        private static final int KEY_SIZE = 128;
        private static final String TRANSFORMATION = "AES/GCM/NoPadding";
        private static final String MESH_KEY_STORE_TYPE = "PKCS12";
        private static final String KEY_ALGORITHM = "AES";
        //keystore与key的提供者不一定是同一个
        //因为keystore中不容许存AES密钥，SunJSSE支持PKCS#12格式，并且容忍了这个错误，
        //而BouncyCastle不允许这样做，并且它只支持BKS格式
        //BKS格式在windows与linux之间共享keystore时出现问题，
        //并且，keystore的密码写死在程序中，并不能提升它的安全性，所以放弃了
        //最终改成将AES的密钥直接存在文件中
        private static final Provider KEYSTORE_PROVIDER = Security.getProvider("SunJSSE");
        private static final Provider KEY_PROVIDER = BouncyBase.PROVIDER;
        protected static final String ROOTKEY_ALIAS = "zhijian.net.cn.rootkey";
        
        //java中storepass、keypass需要相同
        private static final char[] STOREPASS = "yzvJhYIxt39TD9eYc0nFI2".toCharArray();
        private static final char[] KEYPASS = "yzvJhYIxt39TD9eYc0nFI2".toCharArray();
        private final KeyStore keystore;
    
        public PKCSKeyStore(String confPath) throws Exception {
            /* 最好使用硬件信任根，在服务器上无此条件，只能产生一个信任根文件
             * 在安卓中，使用keystore实现（基于TEE），它更加安全。
             * root.keystore用于加密oauth、user服务的codebook、数据密钥，
             * 即使丢失此密钥，也不会影响使用，因为会重新创建密钥及codebook。
             * 但是，对于在至简网格中运行的公司，它们的数据根密钥是用它加密的，
             * 所以，一旦至简网格的root.keystore丢失，则使用数据根密钥加密的数据字段无法解开。
             */
            File ksFile = new File(FileUtil.addPath(confPath, "root.keystore"));
            if(!ksFile.exists()) {
                keystore = createKeyStore(ksFile);
            } else {
                try(FileInputStream fis = new FileInputStream(ksFile)) {
                    keystore = KeyStore.getInstance(MESH_KEY_STORE_TYPE, KEYSTORE_PROVIDER);
                    keystore.load(fis, STOREPASS);
                }
            }
        }
        
        public String decode(String encoded) throws Exception {
            byte[] encodedBytes = ByteUtil.base642bin(encoded);
            byte[] plainBytes = decode(encodedBytes);
            return new String(plainBytes, IConst.DEFAULT_CHARSET);
        }
        
        /**
         * 加密
         * @param plain 明文字符串，在函数中会解开为utf8格式
         * @return 返回二进制的base64格式字符串
         * @throws Exception 算法、io等异常
         */
        public String encode(String plain) throws Exception {
            byte[] cipheredBytes = encode(plain.getBytes(IConst.DEFAULT_CHARSET));
            return ByteUtil.bin2base64(cipheredBytes);
        }
    
        /**
         * 加密
         * @param plain 明文字符串，在函数中会解开为utf8格式
         * @return 返回二进制的base64格式字符串
         * @throws Exception 算法、io等异常
         */
        public byte[] encode(byte[] plain) throws Exception {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, KEY_PROVIDER);
            SecretKey secretKey = getSecretKey(ROOTKEY_ALIAS);
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
         * 解密
         * @param encoded base64格式的字符串，在函数中会解码为二进制
         * @return 返回utf8格式的字符串
         * @throws Exception 算法、io等异常
         */
        public byte[] decode(byte[] encoded) throws Exception {
            int ivLen = encoded[0];
            if(ivLen >= encoded.length) {
                throw new ArrayStoreException("too long iv");
            }
            byte[] iv = new byte[ivLen];
            System.arraycopy(encoded, 1, iv, 0, ivLen);
            byte[] ciphered = new byte[encoded.length - 1 - ivLen];
            System.arraycopy(encoded, 1 + ivLen, ciphered, 0, ciphered.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, KEY_PROVIDER);
            GCMParameterSpec spec = new GCMParameterSpec(KEY_SIZE, iv);
            SecretKey secretKey = getSecretKey(ROOTKEY_ALIAS);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
    
            return cipher.doFinal(ciphered);
        }
    
        /**
         * 用别名查找密钥
         * 如果每次都新建，则无法解密前面加密的数据
         * 此处使用keystore文件，不如安卓中安全、方便。
         * @param alias 密码，创建时指定
         * @return 密钥
         * @throws Exception 算法不存在等异常
         */
        public SecretKey getSecretKey(String alias) throws Exception {
            KeyStore.Entry entry = keystore.getEntry(alias, new KeyStore.PasswordProtection(KEYPASS));
            if(entry != null) {
                return ((KeyStore.SecretKeyEntry)entry).getSecretKey();
            }
            return null;
        }
    
        /**
         * 创建系统根密钥、以及认证密钥对。
         * 实际环境keystore文件应从一个可信根或者加密卡等设备中加载。
         * 比如安卓服务器中使用TEE区实现可信根密钥。
         * @param keystoreFile 文件名
         * @return 密钥
         * @throws Exception 算法不存在等异常
         */
        public static KeyStore createKeyStore(File keystoreFile) throws Exception {
            KeyStore keyStore = KeyStore.getInstance(MESH_KEY_STORE_TYPE, KEYSTORE_PROVIDER);
            keyStore.load(null, null);
    
            //----生成根秘钥，用于加密其他数据---------
            // 通过指定算法,指定提供者来构造KeyGenerator对象
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM, KEY_PROVIDER);
            // 初始化KeyGenerator对象,通过指定大小和随机源的方式产生
            keyGenerator.init(KEY_SIZE, SecureUtil.getRandom());
            SecretKey rootKey = keyGenerator.generateKey();
            // alias为别名，KEYPASS为其密码
            keyStore.setKeyEntry(ROOTKEY_ALIAS, rootKey, KEYPASS, null);
            
            // ROOTPWD密钥库密码
            try(FileOutputStream fos = new FileOutputStream(keystoreFile)) {
                keyStore.store(fos, STOREPASS);
            }
            return keyStore;
        }
    }
}
