package cn.net.zhijian.platform.cmd;

import java.nio.charset.StandardCharsets;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

public class Algorithm extends AbsCommand {
    private static final int FMT_BASE64 = 0;
    private static final int FMT_STDBASE64 = 1;
    private static final int FMT_LOWHEX = 2;
    private static final int FMT_UPHEX = 3;
    
    public Algorithm(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        String cmd = args[0].toLowerCase();
        String[] args1 = StringUtil.removeEle(args, 0);
        if(cmd.equals("password")) {
            return password(args1);
        }

        if(cmd.equals("hash")) {
            return hash(args1);
        }
        
        if(cmd.equals("pbkdf2")) {
            return pbkdf2(args1);
        }
        
        if(cmd.equals("keyid")) {
            return keyid(args1);
        }
        
        if(cmd.equals("uuid")) {
            return uuid(args1);
        }
        
        
        String[] params = args1;
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
        byte[] sha;
        if(cmd.equals("sha1")) {
            sha = sha1(hmac, content);
        } else if(cmd.equals("sha256")) {
            sha = sha256(hmac, content);
        } else if(cmd.equals("md5")) {
            sha = md5(hmac, content);
        } else {
            printHelp(help(), new String[] {"invalid command " + cmd});
            return false;
        }
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
    
    private byte[] md5(boolean hmac, byte[] content) {
        return SecureUtil.md5(content);
    }
    
    private byte[] sha256(boolean hmac, byte[] content) {
        if(hmac) {
            return SecureUtil.hmacSHA256(content);
        }
        return SecureUtil.sha256(content);
    }
    
    private byte[] sha1(boolean hmac, byte[] content) {
        if(hmac) {
            return SecureUtil.hmacSHA1(content);
        }
        return SecureUtil.sha1(content);
    }

    private boolean hash(String[] args) throws Exception {
        if(args.length < 1) {
            printHelp(help());
            return false;
        }
        int start = 0;
        boolean abs = false, integer = false;
        
        if(args[0].equalsIgnoreCase("abs")) {
            start++;
            abs = true;
        }
        if(args[start].equalsIgnoreCase("i")) {
            start++;
            integer = true;
        }
        
        String s = IConst.EMPTY_STR;
        for(int i = start; i < args.length; i++) {
            if(i > start) {
                s += "-";
            }
            s += args[i];
        }
        if(integer) {
            int v = s.hashCode();
            System.out.println(s + "\n" + (abs ? ValParser.absInt(v) : v));
        } else {
            long h = StringUtil.longHashCode(s);
            System.out.println(s + "\n" + (abs ? ValParser.absLong(h) : h));
        }
        return true;
    }
    
    //本质是pbkdf2算法，只是固定了迭代次数
    private boolean password(String[] args) throws Exception {
        if(args.length < 1) {
            printHelp(help());
            return false;
        }
        String pwd = SecureUtil.pbkdf2(SecureUtil.sha256(args[0]), 6);
        System.out.println(pwd);
        return true;
    }
    
    private boolean pbkdf2(String[] args) {
        if(args.length < 1) {
            printHelp(help());
            return false;
        }
        int interCount = 6;
        if(args.length > 1) {
            interCount = Integer.parseInt(args[1]);
        }
        String result = SecureUtil.pbkdf2(args[0], interCount);
        System.out.println("source:" + args[0] + ",interaction count:" + interCount);
        System.out.println(result);
        return true;
    }
    
    private boolean keyid(String[] args) throws Exception {
        if(args.length < 1) {
            printHelp(help());
            return false;
        }
        
        boolean intKey = false;
        int initHash = 0;
        String[] ss = args;
        if(ss[0].equalsIgnoreCase("i")) {
            intKey = true;
            ss = StringUtil.removeEle(ss, 0);
        }
        if(ss[0].matches("\\d+")) {
            initHash = Integer.valueOf(ss[0]);
            ss = StringUtil.removeEle(ss, 0);
        }
        String key = ss[0];
        if(intKey) {
            int h = StringUtil.concatHashCode(initHash, key);
            int id = ValParser.absInt(h);
            System.out.println("int, key=" + key + ", id=" + id);
        } else {
            long h = StringUtil.longHashCode(initHash, key);
            long id = ValParser.absLong(h);
            System.out.println("long, key=" + key + ", id=" + id);
        }
        return true;
    }
    
    private boolean uuid(String[] args) throws Exception {
        System.out.println(StringUtil.base64UUID());
        return true;
    }
    
    @Override
    public String[] help() {
        return new String[] {"sha1,sha256,md5,pbkdf2,password",
            "1)sha1 [hmac] [base64|stdbase64|lowhex|uphex] str",
            "2)sha256 [hmac] [base64|stdbase64|lowhex|uphex] str",
            "3)pbkdf2 str [iteration_count,default 6]",
            "4)password pwd - base64(pbkdf2(sha256(pwd),6))",
            "5)hash [abs] [i] s1 s2 ...",
            "6)keyid [i] [init_hash] string",
            "7)uuid"
        };
    }
}
