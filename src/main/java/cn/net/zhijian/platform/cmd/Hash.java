package cn.net.zhijian.platform.cmd;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

public class Hash extends AbsCommand {
    public Hash(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
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

    @Override
    public String[] help() {
        return new String[]{name + " [abs] [i] s1 s2 ..."};
    }
}
