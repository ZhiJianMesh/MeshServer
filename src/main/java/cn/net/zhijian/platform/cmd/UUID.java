package cn.net.zhijian.platform.cmd;

import cn.net.zhijian.util.StringUtil;

public class UUID extends AbsCommand {
    public UUID(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        System.out.println(StringUtil.base64UUID());
        return true;
    }

    @Override
    public String[] help() {
        return new String[]{"create uuid"};
    }
}
