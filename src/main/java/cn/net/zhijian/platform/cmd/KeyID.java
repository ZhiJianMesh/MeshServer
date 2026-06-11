package cn.net.zhijian.platform.cmd;

import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

public class KeyID extends AbsCommand {
    public KeyID(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
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

    @Override
    public String[] help() {
        return new String[]{
            "calculate string's absolute hash value",
            name + " [i] [init_hash] string"
        };
    }
}
