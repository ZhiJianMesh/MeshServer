package cn.net.zhijian.mesh.frm.intf;

import cn.net.zhijian.mesh.bean.AccessToken;

/**
 * token工人接口
 * @author flyinmind of csdn.net
 *
 */
public interface ITokenWorker extends IOAuth {
    boolean verify(byte[] signBytes, byte[] sign);
    byte[] sign(byte[] signBytes);
    int getSignType();

    /**
     * 返回codebook唯一标识的长度，
     * 用于解决不同公司之间相同cid、uid问题
     * @return 长度值，在分配签名内容时，预先分配这段空间，减少一次内存拷贝
     */
    int identifyLen();

    default AccessToken create(int partition, String caller, String callee, long expiresAt, String ext, int tokenType, int signType) {
        return new AccessToken(this, partition, caller, callee, expiresAt, ext, signType, tokenType);
    }

    default AccessToken create(int partition, String caller, String callee, long expiresAt, String ext, int tokenType) {
        return new AccessToken(this, partition, caller, callee, expiresAt, ext, getSignType(), tokenType);
    }

    /**
     *
     * @param partition 分区
     * @param caller 调用方
     * @param callee 被调方
     * @param ext 服务间token，存的是可以调用的features，用户token存的是cid
     * @param tokenType 令牌类型
     * @return 令牌
     */
    default AccessToken create(int partition, String caller, String callee, String ext, int tokenType) {
        return new AccessToken(this, partition, caller, callee, ext, getSignType(), tokenType);
    }

    ITokenWorker NullTokenWorker = new ITokenWorker() {
        @Override
        public boolean verify(byte[] signBytes, byte[] sign) {
            return true;
        }

        @Override
        public byte[] sign(byte[] signBytes) {
            return null;
        }

        @Override
        public int getSignType() {
            return SIGNTYPE_CODEBOOK;
        }

        @Override
        public int identifyLen() {
            return 0;
        }
    };    
}