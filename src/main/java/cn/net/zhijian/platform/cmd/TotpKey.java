package cn.net.zhijian.platform.cmd;

import java.io.File;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.Totp;
import cn.net.zhijian.util.ValParser;

public class TotpKey extends AbsCommand {
    private static final int CMD_TOTP_TIMESTEP = 60;
    public TotpKey(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
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

    @Override
    public String[] help() {
        return new String[] {"generate totp key",
                name + " your_secret[ code_len[ time_step]]",
                name + " generate[ code_len]"};
    }
}
