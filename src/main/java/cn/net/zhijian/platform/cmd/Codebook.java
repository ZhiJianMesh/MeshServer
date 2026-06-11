package cn.net.zhijian.platform.cmd;

import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.ValParser;

public class Codebook extends AbsCommand {
    public Codebook(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        byte[] codebook = ByteUtil.generate(4096);
        int num = 1;
        byte[] pwd = null;

        if(args.length > 0) { //设置了数量
            num = ValParser.parseInt(args[0], 1);
        }

        if(args.length > 1) {
            pwd = args[1].getBytes();
        } 
        
        StringBuilder sb = new StringBuilder(4096 * num + num);//book1,book2,...
        for(int i = 0; i < num; i++) {
            codebook = ByteUtil.generate(4096);
            if(pwd != null) {
                codebook = SecureUtil.encode(codebook, pwd);
            }
            if(i > 0) {
                sb.append(',');
            }
            sb.append(ByteUtil.bin2base64(codebook));
        }
        System.out.println(sb.toString());
        return true;
    }

    @Override
    public String[] help() {
        return new String[]{
            "create codebooks, if a key prompt, encode it",
            name + " [num] [key]"
        };
    }
}
