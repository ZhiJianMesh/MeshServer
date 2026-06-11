package cn.net.zhijian.mesh.frm.abs;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.pool.AbsResource;
import cn.net.zhijian.util.LogUtil;

/**
 * 虚连接，包括一个默认的连接池实现
 * @author flyinmind of csdn.net
 *
 */
public abstract class AbsConnection extends AbsResource {
    private static final Logger LOG = LogUtil.getInstance();
    
    protected AutoCloseable conn;
    
    public AbsConnection(AutoCloseable conn) {
        this.conn = conn;
    }
    
    /**
     * 获取一个可以关闭的连接
     * 在不同环境中，获取的具体类型不同，比如在服务器上获取的是Connection，
     * 在Android中获取的是SQLiteDatabase
     * @return 获得真实的连接
     */
    public AutoCloseable get() {
        return conn;
    }

    /**
     * 强迫关闭连接，如果在连接池中，默认是归还给连接池
     */
    @Override
    public synchronized void forceClose() {
        if(this.conn == null) {
            return;
        }

        try {
            this.conn.close();
        } catch (Exception e) {
            LOG.error("Fail to close connection", e);
        }
        this.conn = null;
    }
}    

