package cn.net.zhijian.mesh.dbworker;

import org.slf4j.Logger;

import cn.net.zhijian.util.LogUtil;

public class PostgreWorker extends JDBCWorker {
    private static final Logger LOG = LogUtil.getInstance();
    
    public PostgreWorker(JDBCBuilder builder) {
        super(builder);
    }
    
    /**
     * Mysql数据库构造器
     * @author flyinmind of csdn.net
     *
     */
    public static class PostgreBuilder extends JDBCBuilder {
        public PostgreBuilder(int cid, String service, String db,
                String account, String pwd,
                int writeConnNum, int readConnNum, String dbUrl) {
            super(cid, service, db, account, pwd, writeConnNum, readConnNum, dbUrl);
        }

        @Override
        public PostgreWorker build(int dbNo) {
            try {
                return new PostgreWorker(this);
            } catch(Exception e) {
                LOG.error("Fail to create db connection({}.{}.{},cid:{}) to {}", service, db, dbNo, cid, dbUrl, e);
                return null;
            }
        }
    }
}
