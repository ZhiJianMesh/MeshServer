package cn.net.zhijian.mesh.frm.tokenworker;

import java.security.PrivateKey;
import java.security.PublicKey;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.Ecc.EccKeyPair;

/**
 * 使用公钥验签，可以是运维平台的公钥，或应用的公钥
 * OM、APP都基于此类实现
 * @author flyinmind of csdn.net
 *
 */
public class EccTokenWorker implements ITokenWorker {
    private static final Logger LOG = LogUtil.getInstance();

    private PublicKey publicKey;
    private PrivateKey privateKey;
    private Ecc ecc;
    /**
     * 绝大部分情况，signType为SIGNTYPE_APPKEY，
     * 但是在OM服务中为SIGNTYPE_OMKEY，本质是一样的，只是为了区分请求来自OM，
     * 而OM的公钥在每个实例上都已预置，无需到bios中获取
     */
    public final int signType;

    public EccTokenWorker(int ver, PrivateKey privateKey, PublicKey publicKey, int signType) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.signType = signType;
        this.ecc = Ecc.instance(ver);
    }
    
    public EccTokenWorker(EccKeyPair kp, int signType) {
        this.privateKey = kp.prv;
        this.publicKey = kp.pub;
        this.signType = signType;
        this.ecc = Ecc.instance(kp.ver);
    }
    
    public EccTokenWorker(int ver, PrivateKey privateKey, PublicKey publicKey) {
        this(ver, privateKey, publicKey, SIGNTYPE_APPKEY);
    }
    
    public EccTokenWorker(EccKeyPair kp) {
        this(kp, SIGNTYPE_APPKEY);
    }

    public void setKey(EccKeyPair kp) {
        this.ecc = Ecc.instance(kp.ver);
        this.privateKey = kp.prv;
        this.publicKey = kp.pub;
    }
    
    @Override
    public boolean verify(byte[] signBytes, byte[] sign) {
        try {
            if(ecc.verify(signBytes, sign, publicKey)) {
                return true;
            }
        } catch(Exception e) {
            LOG.error("Invalid public key for token,error:{}", e.getMessage());
        }
        if(LOG.isDebugEnabled()) {
            LOG.debug("Verify failed, publicKey:{}", Ecc.publicKey2Str(publicKey));
        }
        return false;
    }

    @Override
    public byte[] sign(byte[] signBytes) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("Sign, paired publicKey:{}", Ecc.publicKey2Str(publicKey));
        }
        return ecc.sign(signBytes, privateKey);
    }

    @Override
    public int getSignType() {
        return signType;
    }

    @Override
    public int identifyLen() {
        return 0;
    }
    
    public String getPublicKey() {
        String sPub = Ecc.publicKey2Str(publicKey);
        return EccKeyPair.toString(null, sPub, ecc.ver);
    }
    
    public EccKeyPair key() {
        return new EccKeyPair(privateKey, publicKey, ecc.ver);
    }
    
    public boolean producible() {
        return this.privateKey != null;
    }
    
    public boolean isEmpty() {
        return this.publicKey == null;
    }
    
    @Override
    public String toString() {
        return getPublicKey();
    }
    
    public static EccTokenWorker nullTokenWorker(int signType) {
        return new EccTokenWorker(Ecc.CUR_VER, null, null, signType);
    }
}