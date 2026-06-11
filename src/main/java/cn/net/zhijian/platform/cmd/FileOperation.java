package cn.net.zhijian.platform.cmd;

import java.io.File;
import java.util.regex.Pattern;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * 版本源文件备份
 */
public class FileOperation extends AbsCommand implements IConst {
    public FileOperation(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if(args.length < 2) {
            printHelp(help());
            return false;
        }
        String act = args[0].toLowerCase();
        String[] ss = StringUtil.removeEle(args, 0);
        if(act.equals("remove")) {
            return remove(ss);
        }
        
        if(act.equals("base64")) {
            return toBase64(ss);
        }
        
        if(act.equalsIgnoreCase("digest")) {
            String s = FileUtil.digest(ss[0]);
            System.out.println(s);
        }
        
        return true;
    }
    
    public boolean toBase64(String[] args) throws Exception {
        if(args.length < 1) {
            printHelp(help());
            return false;
        }
        
        int stdIdx = StringUtil.indexOf(args, "std");
        boolean standard = false;
        if(stdIdx >= 0) {
            args = StringUtil.removeEle(args, stdIdx);
            standard = true;
        }
        if(args.length == 0) {
            printHelp(help());
            return false;
        }
        String file = args[0];
        
        byte[] content = FileUtil.readFile(new File(file), true);
        String s;
        if(standard) {
            s = ByteUtil.stdBase64Encode(content);
        } else {
            s = ByteUtil.bin2base64(content);
        }
        System.out.println(s);
        return true;
    }
    
    private boolean remove(String[] args) {
        if(args.length < 2) {
            printHelp(help());
            return false;
        }
        File root = new File(args[0]);
        Pattern name = Pattern.compile(args[1]);
        int maxSize = Integer.MAX_VALUE;
        if(args.length > 2) {
            maxSize = Integer.parseInt(args[2]);
        }
        int num = removeFiles(root, name, maxSize);
        System.out.println("Total " + num + " files removed");
        return true;
    }
    
    private int removeFiles(File root, Pattern name, int maxSize) {
        File[] files = root.listFiles();
        int num = 0;
        if(files == null || files.length == 0) {
            return 0;
        }
        
        for(File f : files) {
            if(f.isDirectory()) {
                num += removeFiles(f, name, maxSize);
                continue;
            }
            if(f.length() <= maxSize && name.matcher(f.getName()).matches()) {
                System.out.println("Remove " + f);
                f.delete();
                num++;
            }
        }
        return num;
    }
   
    @Override
    public String[] help() {
        return new String[]{
            name + " remove root_path name_pattern [maxSize]",
            name + " digest path_to_file",
            name + " base64 [std] path_to_file"
        };
    }
}
