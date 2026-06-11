package cn.net.zhijian.platform.cmd;

import cn.net.zhijian.util.SecureUtil;

public class Sha256 extends Sha1 {
    public Sha256(String name) {
        super(name);
    }

    @Override
    public String[] help() {
        return new String[] {"calculate sha256 or hmac sha256",
                name + " [hmac] [base64|stdbase64|lowhex|uphex] str"};
    }
    
    @Override
    protected byte[] doFinal(boolean hmac, byte[] content) {
        if(hmac) {
            return SecureUtil.hmacSHA256(content);
        }
        return SecureUtil.sha256(content);
    }
}
