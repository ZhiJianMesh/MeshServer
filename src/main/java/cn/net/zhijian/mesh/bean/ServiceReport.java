package cn.net.zhijian.mesh.bean;

import java.util.Arrays;

import cn.net.zhijian.mesh.frm.intf.IConst;

public final class ServiceReport {
    public final int cid;
    public final String service;
    public final int day;
    public final int[] apis = new int[IConst.DAY_HOURS];
    public final int[] fails = new int[IConst.DAY_HOURS];
    public final int[] excs = new int[IConst.DAY_HOURS];

    public ServiceReport(int cid, String service, int day) {
        this.cid = cid;
        this.service = service;
        this.day = day;
        Arrays.fill(apis, 0);
        Arrays.fill(fails, 0);
        Arrays.fill(excs, 0);
    }
}
