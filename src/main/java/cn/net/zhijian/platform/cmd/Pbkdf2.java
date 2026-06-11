package cn.net.zhijian.platform.cmd;

import cn.net.zhijian.util.SecureUtil;

public class Pbkdf2 extends AbsCommand {
    public Pbkdf2(String name) {
        super(name);
    }

    @Override
    public String[] help() {
        return new String[] {"calculate pbkdf2",
                name + " pwd [iteration_count,default 6]"};
    }

    @Override
    public boolean run(String[] args) {
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
}
