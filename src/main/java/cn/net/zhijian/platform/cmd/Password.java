package cn.net.zhijian.platform.cmd;

import java.io.File;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.Totp;
import cn.net.zhijian.util.ValParser;

/**
 * 公司登录、更新accesscode、修改公司信息等操作。
 * 不能支持register操作，因为没有验证码，这个命令就成了工具工具
 */
public class Password extends AbsCommand {
    private static final int CMD_TOTP_TIMESTEP = 60;
    
    public Password(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if(args.length < 1) {
            printHelp(help());
            return false;
        }
        
        String cmd = args[0].toLowerCase();
        String[] args1 = StringUtil.removeEle(args, 0);
        if(cmd.equals("password")) {
            return encodePassword(args1);
        }

        if(cmd.equals("totp")) {
            return totp(args1);
        }
        
        printHelp(help());
        return false;
    }

    @Override
    public String[] help() {
        return new String[]{name + ",a set of password command",
            "1)password pwd - base64(pbkdf2(sha256(pwd),6))",
            "2)totp your_secret[ code_len[ time_step]] | generate[ code_len]"
        };
    }

    private boolean encodePassword(String[] args) throws Exception {
        if(args.length < 1) {
            printHelp(help());
            return false;
        }
        String pwd = SecureUtil.pbkdf2(SecureUtil.sha256(args[0]), 6);
        System.out.println(pwd);
        return true;
    }

    private boolean totp(String[] args) throws Exception {
        int timeStep = CMD_TOTP_TIMESTEP;
        if(args.length < 1) {
            File cfgFile = new File(FileUtil.addPath(configDir, "totp.key"));
            if(!cfgFile.exists()) {
                printHelp(help());
                return false;
            }
            String secret = FileUtil.readFile(cfgFile, IConst.DEFAULT_CHARSET);
            Totp totp = new Totp(Totp.DEFAULT_CODE_DIGITS, timeStep);
            long leftTime = timeStep - (System.currentTimeMillis() / 1000 - totp.currentTime() * timeStep);
            System.out.println(totp.generateCode(secret) + ",left time:" + leftTime);
            return true;
        }
        int codeLen = Totp.DEFAULT_CODE_DIGITS;
        if(args.length > 1) {
            codeLen = ValParser.parseInt(args[1], Totp.DEFAULT_CODE_DIGITS);
        }
        
        if(args[0].equalsIgnoreCase("generate")) {
            System.out.println(Totp.generateSecret(codeLen));
        }

        if(args.length > 2) {
            codeLen = ValParser.parseInt(args[2], Totp.DEFAULT_CODE_DIGITS);
        }

        Totp totp = new Totp(codeLen, timeStep);
        long leftTime = timeStep - (System.currentTimeMillis() / 1000 - totp.currentTime() * timeStep);
        System.out.println(totp.generateCode(args[0]) + ",left time:" + leftTime);
        
        return true;
    }
}
