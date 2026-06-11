package cn.net.zhijian.mesh.bean;

/**
 * 具有有效期的对象
 * @author flyinmind of csdn.net
 *
 */
public class ExpirableEle {
    /**
     * 默认缓存时间，5分钟，
     * 比如服务间的token缓存，5分钟，有利于发现更新。
     * 还有比如服务节点缓存信息，5分钟有利于及时更新配置，并且也不会太频繁的更新。
     */
    public static final int DEFAULT_EXPIRES_IN = 10 * 60;

    protected long expiresAt; //单位毫秒

    public ExpirableEle(int expiresIn) {
        if(expiresIn > 0) {
            this.expiresAt = System.currentTimeMillis() + 1000L * expiresIn;
        } else {
            this.expiresAt = 0;
        }
    }

    public long expiresAt() {
        return expiresAt;
    }
    
    public boolean expired() {
        if(expiresAt <= 0) {
            return false;
        }
        return System.currentTimeMillis() >= expiresAt;
    }
}