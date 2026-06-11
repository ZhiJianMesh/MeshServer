package cn.net.zhijian.platform.cmd;

import java.text.SimpleDateFormat;
import java.util.Date;

public class Utc extends AbsCommand {
    public Utc(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if(args.length > 0) {
            String v = "";
            for(String s : args) {
                v += s; //支持逗号分隔
            }
            v = v.toLowerCase();
            long utcTime;
            if(v.endsWith("m")) {
                utcTime = Long.valueOf(v.substring(0, v.length() - 1)) * 60000;
            } else if(v.endsWith("h")) {
                utcTime = Long.valueOf(v.substring(0, v.length() - 1)) * 3600000;
            } else {
                utcTime = Long.valueOf(v);
            }
            Date dt = new Date(utcTime);
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            System.out.println(fmt.format(dt));
        } else {
            System.out.println(System.currentTimeMillis());
        } 
        return true;
    }

    @Override
    public String[] help() {
        return new String[]{name + " [utc-value]"};
    }
}
