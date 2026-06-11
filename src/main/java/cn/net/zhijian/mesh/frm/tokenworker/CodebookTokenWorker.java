package cn.net.zhijian.mesh.frm.tokenworker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * 使用密码本做加解密的token工人
 * @author flyinmind of csdn.net
 *
 */
public class CodebookTokenWorker implements ITokenWorker {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String CODEBOOKS_SPLITER = ",";

    private static final SecureRandom rand = SecureUtil.getRandom();
    private final List<byte[]> codebooks = new ArrayList<>();
    /*
     * 密码本唯一标识，多个密码本的hmacSha256值，用于解决窜访问题。
     * 比如，一个用户在自己的公司登录后，再用这个token去其他公司登录，
     * 只要uid、cid相同，将会畅通无阻。
     * 如果有密码本的identify，只要密码本不同，则不同系统的token不可互通。
     */
    private final byte[] identify;
    private final int identifyLen;

    public CodebookTokenWorker(List<byte[]> codebooks) throws IOException {
        if(codebooks == null) {
            throw new IOException("Invalid codebooks");
        }
        //生成密码本签名，使得不同密码本的签名不可互通
        this.codebooks.addAll(codebooks);
        try(ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            for (byte[] codebook : codebooks) {
                baos.write(codebook);
            }
            byte[] content = baos.toByteArray();
            byte[] key = SecureUtil.md5(content);
            this.identify = SecureUtil.hmacSHA256(content, key);
            this.identifyLen = this.identify == null ? 0 : this.identify.length;
        }
    }

    /**
     * 使用密码本验签，在服务间认证（oAuth）与user认证中使用
     * @param signBytes 待签名内容，如果有identify，则预先分配好了空间
     * @param sign 前四个字节是两个short，表示密码的高8字节与低8字节在codebook中的位置
     * @return 验证签名结果
     */
    @Override
    public boolean verify(byte[] signBytes, byte[] sign) {
        int no = sign[0];
        if(no >= codebooks.size() || no < 0) {
            LOG.error("Invalid codebook no({}) when using codebook to verify", no);
            return false;
        }
        byte[] codebook = codebooks.get(no);
        int p1 = ByteUtil.parseShort(sign, 1);
        if(p1 < 0 || p1 > codebook.length - 8) {
            LOG.error("Invalid codebook p1({}) when using codebook to verify", p1);
            return false;
        }
        int p2 = ByteUtil.parseShort(sign, 3);
        if(p2 < 0 || p2 > codebook.length - 8) {
            LOG.error("Invalid codebook p2({}) when using codebook to verify", p2);
            return false;
        }

        byte[] pwd = new byte[16];
        System.arraycopy(codebook, p1, pwd, 0, 8);
        System.arraycopy(codebook, p2, pwd, 8, 8);
        System.arraycopy(this.identify, 0, signBytes, signBytes.length - identifyLen, identifyLen);
        byte[] chkSign = SecureUtil.hmacSHA256(signBytes, pwd);
        if(chkSign == null) {
            LOG.error("Fail to calculate sign");
            return false;
        }
        if(!ByteUtil.byteArrayEquals(sign, 5, chkSign, 0, chkSign.length)) {
            if(LOG.isDebugEnabled()) {
                LOG.debug("sign:{},checkSign:{}", ByteUtil.bin2hex(sign, 5, sign.length - 5), ByteUtil.bin2hex(chkSign));
            }
            return false;
        }
        return true;
    }

    @Override
    public byte[] sign(byte[] signBytes) {
        int size = codebooks.size();
        int no = rand.nextInt(size);
        byte[] codebook = codebooks.get(no);
        int p1 = rand.nextInt(codebook.length - 8);
        int p2 = rand.nextInt(codebook.length - 8);

        byte[] pwd = new byte[16];
        System.arraycopy(codebook, p1, pwd, 0, 8);
        System.arraycopy(codebook, p2, pwd, 8, 8);
        System.arraycopy(this.identify, 0, signBytes, signBytes.length - identifyLen, identifyLen);
        byte[] sign = SecureUtil.hmacSHA256(signBytes, pwd);
        byte[] buff = new byte[5 + sign.length];
        buff[0] = (byte)no;
        ByteUtil.encodeShort(buff, p1, 1);
        ByteUtil.encodeShort(buff, p2, 3);
        System.arraycopy(sign, 0, buff, 5, sign.length);

        return buff;
    }

    @Override
    public int getSignType() {
        return SIGNTYPE_CODEBOOK;
    }

    @Override
    public int identifyLen() {
        return this.identifyLen;
    }

    /**
     * 产生随机的密码本，并使用配置密钥加密
     * @param num 数量
     * @return 多个密码本，之间使用逗号分隔
     */
    public static String generateCodebooks(int num) {
        StringBuilder sb = new StringBuilder(4096);
        for(int i = 0; i < num; i++) {
            String codebook = ByteUtil.bin2base64(ByteUtil.generate(4096));
            if(i > 0) {
                sb.append(CODEBOOKS_SPLITER);
            }
            sb.append(codebook);
        }
        return sb.toString();
    }

    public static List<byte[]> parseCodebooks(String cbStr) {
        List<byte[]> codebooks = new ArrayList<>();
        String[] sCodebooks = StringUtil.str2List(cbStr, CODEBOOKS_SPLITER, null);
        for(int i = 0; i < sCodebooks.length; i++) {
            byte[] codebook = ByteUtil.base642bin(sCodebooks[i]);
            if(codebook == null || codebook.length > 4096) {
                LOG.error("Fail to parse codebook {}, length is bigger than 4096", i);
                return null;
            }
            codebooks.add(codebook);
        }
        return codebooks;
    }
}


