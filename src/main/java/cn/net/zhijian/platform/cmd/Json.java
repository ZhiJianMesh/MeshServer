package cn.net.zhijian.platform.cmd;

import java.io.File;

import cn.net.zhijian.util.JsonValidater;
import cn.net.zhijian.util.JsonValidater.JsonFormatException;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.JsonUtil;

public class Json extends AbsCommand {
    public Json(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if(args.length < 2) {
            printHelp(help());
            return false;
        }
        String cmd = args[0];
        if(cmd.equals("simplify")) {
            String s = JsonUtil.readFileWithCmtsAndLFInStr(new File(args[1]), IConst.EMPTY_STR);
            System.out.println(s);
        } else  if(cmd.equals("verify")) {
            File f = new File(args[1]);
            if(!f.exists()) {
                System.out.println("`" + f + "` not exists");
                return false;
            }
            if(f.isDirectory()) {
                if(args.length > 2) {
                    String ext = args[2].toLowerCase();
                    validateDir(f, ext);
                } else {
                    File[] files = f.listFiles();
                    if(files != null) {
                        for(File file : files) {
                            validateFile(file);
                        }
                    }
                }
            } else {
                validateFile(f);
            }
        } else {
            printHelp(help());
            return false;
        }
        
        return true;
    }

    private void validateFile(File f) {
        try {
            String s = FileUtil.readFile(f, IConst.DEFAULT_CHARSET);
            JsonValidater jv = new JsonValidater(s, true);
            jv.validate();
        } catch(Exception e) {
            System.out.println("File:" + f);
            if(e instanceof JsonFormatException) {
                JsonFormatException jfe = (JsonFormatException)e;
                System.out.println("Wrong @(line:" + jfe.line + ",col:" + jfe.col + "),message:" + jfe.getMessage());
            } else {
                e.printStackTrace();
            }
        }        
    }
    
    private void validateDir(File f, String ext) {
        File[] files = f.listFiles();
        if(files == null) {
            return;
        }
        for(File file : files) {
            if(file.isDirectory()) {
                validateDir(file, ext);
            } else {
                if(file.getName().toLowerCase().endsWith(ext)) {
                    validateFile(file);
                }
            }
        }
    }
    
    @Override
    public String[] help() {
        return new String[]{name + ",json file operations",
                "1)simplify src_json_file",
                "  remove all comments and line feeds",
                "2)verify src_json_file_or_dir [extension]",
                "  remove all comments and line feeds"
        };
    }
}
