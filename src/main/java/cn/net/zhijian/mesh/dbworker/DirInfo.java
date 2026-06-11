package cn.net.zhijian.mesh.dbworker;

public final class DirInfo {
    public final long id;
    public final String name;
    public final long ut; // update time

    public DirInfo(long id, String name, long ut) {
        this.id = id;
        this.name = name;
        this.ut = ut;
    }
}