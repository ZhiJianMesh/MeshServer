package cn.net.zhijian.platform.cmd;

import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.Ecc.EccKeyPair;

public class EccKey extends AbsCommand {
    public EccKey(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        Ecc ecc;
        if(args.length > 0) {
            ecc = Ecc.instance(Integer.parseInt(args[0]));
        } else {
            ecc = Ecc.instance();//产生最新版本的密钥对
        }
        if(ecc == null) {
            System.out.println("Invalid version no " + args[0]);
            printHelp(help());
            return false;
        }
        EccKeyPair kp = ecc.genKeyPair();
        
        if(args.length > 1) {
            String s = kp.toString(args[1]);
            System.out.println("KEY_PAIR(ver+encrypted(prv)+&+pub):" + s);
        } else {
            String s = kp.toString();
            System.out.println("KEY_PAIR(ver+prv+&+pub):" + s);
        }
        
        EccKeyPair kp1 = new EccKeyPair(null, kp.pub, kp.ver);
        String s = kp1.toString(); //携带了版本号，并且有一个冗余的&
        System.out.println("PUBLIC-KEY:" + s);
        s = Ecc.privateKey2Str(kp.prv);
        System.out.println("PRIVATE-KEY:" + s);
        
        return true;
    }

    @Override
    public String[] help() {
        return new String[]{"create ecc key pair",
                name + " [ver [pwd]]"};
    }
}
