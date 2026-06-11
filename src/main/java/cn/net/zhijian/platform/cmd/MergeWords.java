package cn.net.zhijian.platform.cmd;

import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.StringSpliter;

public class MergeWords extends AbsCommand {
    public MergeWords(String name) {
        super(name);
    }

    @Override
    public String[] help() {
        return new String[]{
            "merge all word files into one file",
            name + " path_to_word_files [path_to_dest_file]"
        };
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if(args.length < 1) {
            printHelp(help());
            return false;
        }
        String wordFilesDir = args[0];
        String destFile;
        if(args.length > 1) {
            destFile = args[1];
        } else {
            String root = System.getProperty("user.dir");
            destFile = FileUtil.addPath(root, "words.txt");
        }

        StringSpliter.mergeDictionaries(wordFilesDir, destFile, ".dic");
        return false;
    }
}
