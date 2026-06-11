package cn.net.zhijian.mesh.dbworker;

import cn.net.zhijian.util.ValParser;

public final class ItemInfo {
    public final String name;
    public final String val;
    public final long ut; // update time

    public ItemInfo(String name, String val, long ut) {
        this.name = name;
        this.val = val;
        this.ut = ut;
    }
    
    public ItemInfo(String name, Object[] line) { //val,update_time
        this.name = name;
        String val = ValParser.parseString(line[0]);
        long ut = ValParser.parseLong(line[1], 0L);
        this.val = val;
        this.ut = ut;
    }
}
