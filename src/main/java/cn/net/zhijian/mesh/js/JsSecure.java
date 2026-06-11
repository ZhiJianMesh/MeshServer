package cn.net.zhijian.mesh.js;

import java.security.PrivateKey;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.quickjs.JavascriptMethod;
import cn.net.zhijian.util.AESUtil;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;

/**
 * JS中安全相关的接口
 * @author flyinmind of csdn.net
 *
 */
public final class JsSecure implements IConst {
    private static final Logger LOG = LogUtil.getInstance();

    @JavascriptMethod
    public String pbkdf2(String pwd, int iterationCount) {
        return SecureUtil.pbkdf2(pwd, iterationCount);
    }

    @JavascriptMethod
    public boolean pbkdf2Check(String pwd, String savedPwd) {
        return SecureUtil.pbkdf2Check(pwd, savedPwd);
    }

    @JavascriptMethod
    public String cbcEncrypt(String plain, String key) {
        byte[] plainBytes = plain.getBytes(DEFAULT_CHARSET);
        byte[] keyBytes = key.getBytes(DEFAULT_CHARSET);
        byte[] cipher = AESUtil.cbcEncrypt128(plainBytes, keyBytes);
        return ByteUtil.bin2base64(cipher);
    }

    @JavascriptMethod
    public String cbcDecrypt(String cihper, String key) {
        byte[] cipherBytes = ByteUtil.base642bin(cihper);
        byte[] keyBytes = key.getBytes(DEFAULT_CHARSET);
        byte[] plain = AESUtil.cbcDecrypt128(cipherBytes, keyBytes);
        return new String(plain, DEFAULT_CHARSET);
    }

    /**
     * 
     * @param plain 明文
     * @param pubKey 公钥，以密钥对方式保存，最前面需要带版本号与&
     * @return 密文
     */
    @JavascriptMethod
    public String eccEncrypt(String plain, String pubKey) {
        try {
            EccKeyPair ekp = EccKeyPair.parse(pubKey);
            return ekp.encrypt(plain);
        } catch (Exception e) {
            LOG.error("Fail to call eccEncode", e);
        }
        return IConst.EMPTY_STR;
    }

    @JavascriptMethod
    public String eccDecrypt(String cipher, String priKey) {
        int ver = ByteUtil.getBase64CharVal(priKey.charAt(0));
        Ecc ecc = Ecc.instance(ver);
        byte[] bPrv = ByteUtil.stdBase64Decode(priKey.substring(1));
        try {
            PrivateKey privateKey = ecc.privateKey(bPrv);
            EccKeyPair ekp = new EccKeyPair(privateKey, null, ver);
            return ekp.decrypt(cipher);
        } catch (Exception e) {
            LOG.error("Fail to call eccDecrypt", e);
        }
        return IConst.EMPTY_STR;
    }

    /**
     * 产生密钥对，并使用pwd加密后返回
     * @param pwd 密钥对的密码
     * @return 密钥对，version(1,max 63) + encrypted(private_key) + & + publicKey
     */
    @JavascriptMethod
    public String keyPair(String pwd) {
        try {
            return Ecc.keypair2Str(pwd);
        } catch (Exception e) {
            LOG.error("Fail to generate ecc key pair", e);
            return IConst.EMPTY_STR;
        }
    }
    
    /**
     * 从密钥对中获取公钥
     * @param kp 密钥对
     * @return 公钥
     */
    @JavascriptMethod
    public String publicKey(String kp) {
        try {
            EccKeyPair ekp = EccKeyPair.parse(kp, true);
            return ekp.publicKey2Str();
        } catch (Exception e) {
            LOG.error("Fail to get public key from key pair", e);
            return IConst.EMPTY_STR;
        }
    }

    /**
     * 从密钥对中获取私钥
     * @param kp 密钥对
     * @return 私钥，此私钥未加密
     */
    @JavascriptMethod
    public String privateKey(String kp, String pwd) {
        try {
            EccKeyPair ekp = EccKeyPair.parse(kp, pwd);
            return IConst.EMPTY_STR + ByteUtil.getBase64CharByVal(ekp.ver)
                + Ecc.privateKey2Str(ekp.prv);
        } catch (Exception e) {
            LOG.error("Fail to get private key from key pair", e);
            return IConst.EMPTY_STR;
        }
    }
    
    /**
     * 密钥对加密
     * @param kp 密钥对，私钥已加密
     * @param plain 明文
     * @return 密文
     */
    @JavascriptMethod
    public String keyPairEncrypt(String kp, String plain) {
        try {
            EccKeyPair ekp = EccKeyPair.parse(kp, true);
            return ekp.encrypt(plain);
        } catch (Exception e) {
            LOG.error("Fail to encrypt by key pair", e);
            return IConst.EMPTY_STR;
        }
    }

    /**
     * 密钥对解密
     * @param kp 密钥对，私钥已加密
     * @param pwd 密钥对密码，用以加密私钥
     * @param cipher 密文
     * @return 明文内容
     */
    @JavascriptMethod
    public String keyPairDecrypt(String kp, String pwd, String cipher) {
        try {
            EccKeyPair ekp = EccKeyPair.parse(kp, pwd);
            return ekp.decrypt(cipher);
        } catch (Exception e) {
            LOG.error("Fail to decrypt by key pair", e);
            return IConst.EMPTY_STR;
        }
    }

    /**
     * 修改密钥对密码
     */
    @JavascriptMethod
    public String changeKeyPairPwd(String kp, String oldPwd, String newPwd) {
        try {
            return Ecc.changeKeyPairPwd(kp, oldPwd, newPwd);
        } catch (Exception e) {
            LOG.error("Fail to decrypt by key pair", e);
            return IConst.EMPTY_STR;
        }
    }
    
    @JavascriptMethod
    public String gcmEncrypt(String plain, String key) {
        byte[] plainBytes = plain.getBytes(DEFAULT_CHARSET);
        byte[] keyBytes = key.getBytes(DEFAULT_CHARSET);
        byte[] cipher = AESUtil.gcmEncrypt128(plainBytes, keyBytes);
        return ByteUtil.bin2base64(cipher);
    }

    @JavascriptMethod
    public String gcmDecrypt(String cihper, String key) {
        byte[] cipherBytes = ByteUtil.base642bin(cihper);
        byte[] keyBytes = key.getBytes(DEFAULT_CHARSET);
        byte[] plain = AESUtil.gcmDecrypt128(cipherBytes, keyBytes);
        return new String(plain, DEFAULT_CHARSET);
    }

    @JavascriptMethod
    public String md5(String s) {
        return SecureUtil.md5WithBase64(s);
    }

    /**
     * [切记]不可以使用不定参数的形式，在quickjs中会出现异常，并且导致整个程序终止。
     * 并且传递的参数个数也必须与java中定义的一致，否则也会导致程序终止，并且即使捕捉此异常，也无法阻止程序退出
     * @param s 字符串
     * @return 签名后的字符串
     */
    @JavascriptMethod
    public String sha256(String s) {
        return SecureUtil.sha256(s);
    }

    @JavascriptMethod
    public String sha1(String s) {
        byte[] sha = SecureUtil.sha1(s.getBytes(DEFAULT_CHARSET));
        return ByteUtil.bin2base64(sha);
    }

    @JavascriptMethod
    public String hmacSHA256(String s) {
        byte[] sha = SecureUtil.hmacSHA256(s.getBytes(DEFAULT_CHARSET));
        return ByteUtil.bin2base64(sha);
    }

    @JavascriptMethod
    public boolean hmacSHA256Check(String s, String saved) {
        byte[] savedBytes = ByteUtil.base642bin(saved);
        return SecureUtil.hmacSHA256Check(s.getBytes(DEFAULT_CHARSET), savedBytes);
    }

    @JavascriptMethod
    public String hmacSHA1(String s, String key) {
        byte[] sha = SecureUtil.hmacSHA1(s.getBytes(DEFAULT_CHARSET), key.getBytes(DEFAULT_CHARSET));
        return ByteUtil.bin2base64(sha);
    }
    
    @JavascriptMethod
    public boolean isPwdStrong(String acc, String pwd, int min, int charTypeNum, int diffCharNum) {
        return SecureUtil.isPwdStrong(acc, pwd, min, charTypeNum, diffCharNum);
    }
}
