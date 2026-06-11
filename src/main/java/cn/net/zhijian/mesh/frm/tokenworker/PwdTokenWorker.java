package cn.net.zhijian.mesh.frm.tokenworker;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;

/**
 * 使用密码做加解密的token工人
 * 用于零时赋予权限的场景
 * @author flyinmind of csdn.net
 *
 */
public class PwdTokenWorker implements ITokenWorker {
    private static final Logger LOG = LogUtil.getInstance();
    private final byte[] pwd;

    public PwdTokenWorker(String pwd) {
        this.pwd = pwd.getBytes(DEFAULT_CHARSET);
    }

    /**
     * 使用密码验签，只能在Console中使用
     * @param signBytes 待签名内容，如果有identify，则预先分配好了空间
     * @param sign 前四个字节是两个short，表示密码的高8字节与低8字节在codebook中的位置
     * @return 验证签名结果
     */
    @Override
    public boolean verify(byte[] signBytes, byte[] sign) {
        byte[] chkSign = SecureUtil.hmacSHA256(signBytes, pwd);
        if(chkSign == null) {
            LOG.error("Fail to calculate pwd sign");
            return false;
        }
        if(sign == null || chkSign.length != sign.length) {
            return false;
        }
        for(int i = 0; i < sign.length; i++) {
            if(chkSign[i] != sign[i]) {
                if(LOG.isDebugEnabled()) {
                    LOG.debug("sign:{},checkSign:{}", ByteUtil.bin2hex(sign, 5, sign.length - 5), ByteUtil.bin2hex(chkSign));
                }
                return false;
            }
        }
        return true;
    }

    @Override
    public byte[] sign(byte[] signBytes) {
        return SecureUtil.hmacSHA256(signBytes, pwd);
    }

    @Override
    public int getSignType() {
        return SIGNTYPE_PWD;
    }

    @Override
    public int identifyLen() {
        return 0;
    }
}

