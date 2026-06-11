package cn.net.zhijian.platform.cmd;

import cn.net.zhijian.util.SecureUtil;

public class Md5 extends Sha1 {
    public Md5(String name) {
        super(name);
    }

    @Override
    public String[] help() {
        return new String[] {"calculate md5",
                name + " [base64|stdbase64|lowhex|uphex] str"};
    }
    
    @Override
    protected byte[] doFinal(boolean hmac, byte[] content) {
        return SecureUtil.md5(content);
    }
}
