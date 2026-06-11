package cn.net.zhijian.platform.cmd;

import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.util.MapBuilder;

public class DB extends AbsCommand {
    public DB(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if(args == null || args.length < 1) {
            printHelp(help());
            return false;
        }
        
        HandleResult result;
        String cmd = args[0].toLowerCase();
        if(cmd.equals("restore")) {
            result = postCommand("restore", null).get(300, TimeUnit.SECONDS);
        } else if(cmd.equals("backup")) {
            result = postCommand("backup", null).get(300, TimeUnit.SECONDS);
        } else if(cmd.equals("backupat")) {
            if(args.length < 2) {
                printHelp(help());
                return false;
            }
            int hour, minute = 0;
            int pos = args[1].indexOf(':');
            if(pos < 0) {
                hour = Integer.parseInt(args[1]);
            } else {
                hour = Integer.parseInt(args[1].substring(0, pos));
                minute = Integer.parseInt(args[1].substring(pos + 1));
            }
            if(hour > 24 || hour < 0 || minute < 0 || minute > 59) {
                printHelp(help());
                return false;
            }
            int offset = TimeZone.getDefault().getRawOffset() / 60000; //分钟数
            int at = hour * 60 + minute - offset;
            if(at < 0) {
                at += 1440;
            } else if(at > 1440) {
                at -= 1440;
            }
            System.out.println("set backup at:" + at);
            Map<String, Object> params = MapBuilder.of("at", at);
            result = postCommand("at", params).get(10, TimeUnit.SECONDS);
        } else {
            printHelp(help());
            return false;
        }
        
        return result.code == RetCode.OK;
    }

    
    @Override
    public String[] help() {
        return new String[] {
            name + " backup|restore|at",
            "1)backup",
            "2)restore",
            "3)backupat HH:mm",
            "  HH<24, mm<60"
        };
    }
}
