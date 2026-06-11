package cn.net.zhijian.mesh.bean;

/**
 * 服务器状态
 * @author flyinmind of csdn.net
 *
 */
public final class ServerState {
    public static final int NULL = 0;
    public static final int INITIALIZED = 1;
    public static final int ALTERING = 2;
    public static final int RUNNING = 3;
    public static final int CLOSED = 4;

    private long startupTime;
    private int state;

    public ServerState(int state) {
        this.startupTime = System.currentTimeMillis();
        this.state = state;
    }

    public ServerState() {
        this(NULL);
    }

    public boolean isRunning() {
        return isRunning(state);
    }

    public static boolean isRunning(int state) {
        return state == RUNNING;
    }

    public int state() {
        return state;
    }

    public void state(int state) {
        this.state = state;
    }

    public void startupTime(long startupTime) {
        //如果重启过，只要中断时间不超过一小时，都以第一次启动时间点为准
        if(startupTime - this.startupTime > 3600000) {
            this.startupTime = startupTime;
        }
    }

    public long startupTime() {
        return this.startupTime;
    }
    
    @Override
    public String toString() {
        return "{state:" + state + ",startup_time:" + this.startupTime + "}";
    }
}
