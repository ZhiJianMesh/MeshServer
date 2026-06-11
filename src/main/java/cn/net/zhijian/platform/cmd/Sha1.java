package cn.net.zhijian.platform.cmd;

import java.nio.charset.StandardCharsets;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;

public class Sha1 extends AbsCommand {
    private static final int FMT_BASE64 = 0;
    private static final int FMT_STDBASE64 = 1;
    private static final int FMT_LOWHEX = 2;
    private static final int FMT_UPHEX = 3;
    
    public Sha1(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        String[] params = args;
        int format = FMT_BASE64; //0:base64,1:stdbase64,2:lowhex,3:uphex
        int idx = StringUtil.indexOf(params, "base64");
        if(idx >= 0) {
            params = StringUtil.removeEle(params, idx);
            format = FMT_BASE64;
        } else if((idx = StringUtil.indexOf(params, "stdbase64")) >= 0){
            params = StringUtil.removeEle(params, idx);
            format = FMT_STDBASE64;
        } else if((idx = StringUtil.indexOf(params, "lowhex")) >= 0){
            params = StringUtil.removeEle(params, idx);
            format = FMT_LOWHEX;
        } else if((idx = StringUtil.indexOf(params, "uphex")) >= 0){
            params = StringUtil.removeEle(params, idx);
            format = FMT_UPHEX;
        }
        
        boolean hmac = false;
        if((idx = StringUtil.indexOf(params, "hmac")) >= 0) {
            params = StringUtil.removeEle(params, idx);
            hmac = true;
        }
        
        if(params.length == 0) {
            printHelp(help());
            return false;
        }
        
        byte[] content = params[0].getBytes(StandardCharsets.UTF_8);
        byte[] sha = doFinal(hmac, content);
        String s = IConst.EMPTY_STR;
        String fmt = IConst.EMPTY_STR;
        switch(format) {
        case FMT_BASE64:
            s =  ByteUtil.bin2base64(sha);
            fmt = "base64";
            break;
        case FMT_STDBASE64:
            s =  ByteUtil.stdBase64Encode(sha);
            fmt = "standard base64";
            break;
        case FMT_LOWHEX:
            s =  ByteUtil.bin2hex(sha, false);
            fmt = "lowwer hex";
            break;
        case FMT_UPHEX:
            s =  ByteUtil.bin2hex(sha, true);
            fmt = "upper hex";
            break;
        }
        System.out.println("Original string:" + params[0] + ",format:" + fmt);
        System.out.println(s);
        
        return true;
    }

    @Override
    public String[] help() {
        return new String[] {"calculate sha1 or hmac sha1",
                name + " [hmac] [base64|stdbase64|lowhex|uphex] str"};
    }
    
    protected byte[] doFinal(boolean hmac, byte[] content) {
        if(hmac) {
            return SecureUtil.hmacSHA1(content);
        }
        return SecureUtil.sha1(content);
    }
}
