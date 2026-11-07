package cn.net.zhijian.platform.cmd;

import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.DateUtil.PeriodType;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

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
            return backupAt(StringUtil.removeEle(args, 0));
        } else {
            printHelp(help());
            return false;
        }
        
        return result.code == RetCode.OK;
    }

    
    private boolean backupAt(String[] args) throws Exception {
        int offset = TimeZone.getDefault().getRawOffset() / 60000; //分钟数
        try {
            if(args.length > 0) {
                CompanyInfo localCompany = getLocalCompany();
                int hour, minute = 0;
                int pos = args[0].indexOf(':');
                if(pos < 0) {
                    hour = Integer.parseInt(args[0]);
                } else {
                    hour = Integer.parseInt(args[0].substring(0, pos));
                    minute = Integer.parseInt(args[0].substring(pos + 1));
                }
                if(hour > 24 || hour < 0 || minute < 0 || minute > 59) {
                    printHelp(help());
                    return false;
                }
                int at = hour * 60 + minute - offset;
                if(at < 0) {
                    at += 1440;
                } else if(at > 1440) {
                    at -= 1440;
                }
                String sAt = String.format("%02d:%02d", hour, minute);
                System.out.println("set backup at:" + sAt);

                localCompany.setBackupAt(at).whenComplete((hr, e) -> {
                    if(e != null) {
                        System.out.println("Fail to set backupAt:" + e.getMessage());
                        return;
                    }
                    System.out.println("set backupAt " + sAt + " result:" + hr.brief());
                }).get(10, TimeUnit.SECONDS);
            } else {
                CompanyInfo localCompany = getLocalCompany();
                localCompany.getBackupAt().whenComplete((hr, e) -> {
                    if(e != null) {
                        System.out.println("Fail to get backupAt:" + e.getMessage());
                        return;
                    }
                    if(hr.code != RetCode.OK) {
                        System.out.println("Fail to get backupAt:" + hr.brief());
                        return;
                    }
                    int backupAt = ValParser.getAsInt(hr.data, "backupAt");
                    int at = backupAt + offset;
                    String sAt = String.format("%02d:%02d", at / 60, at % 60);
                    long recent = ValParser.getAsLong(hr.data, "recent");
                    long nextBackup = DateUtil.recentNextTime(PeriodType.DAY, 0, backupAt * 60000);
                    System.out.println("backupAt:" + sAt
                            + ",recent:" + DateUtil.utcToLocale(recent)
                            + ",nextBackupAt:" + DateUtil.utcToLocale(nextBackup));
                }).get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }
    
    @Override
    public String[] help() {
        return new String[] {
            name + " backup|restore|at",
            "1)backup",
            "2)restore",
            "3)backupat [HH:mm]",
            "  HH<24, mm<60"
        };
    }
}
