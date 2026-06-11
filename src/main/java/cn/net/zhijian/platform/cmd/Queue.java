package cn.net.zhijian.platform.cmd;

import java.io.File;

import cn.net.zhijian.fileq.io.ConsumeState;
import cn.net.zhijian.mesh.frm.intf.IConst;

/**
 * 版本源文件备份
 */
public class Queue extends AbsCommand implements IConst {
    public Queue(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if(args.length < 1) {
            printHelp(help());
            return false;
        }
        File stateFile = new File(args[0]);
        if(!stateFile.exists()) {
            printHelp(help());
            System.out.println("state file not exists");
            return false;
        }
        try(ConsumeState state = new ConsumeState(stateFile, 100)) {
            System.out.println("File no:" + state.fileNo() + ",read position:" + state.readPos());
        }
        return true;
    }
   
    @Override
    public String[] help() {
        return new String[]{
            name + " path_to_state_file"
        };
    }
}
