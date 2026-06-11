package cn.net.zhijian.mesh.dbworker;

import org.slf4j.Logger;

import cn.net.zhijian.util.LogUtil;

public class MysqlWorker extends JDBCWorker {
    public MysqlWorker(MysqlBuilder builder) {
        super(builder);
    }
    
    /**
     * Mysql数据库构造器
     * @author flyinmind of csdn.net
     *
     */
    public static class MysqlBuilder extends JDBCBuilder {
        private final Logger LOG = LogUtil.getInstance();
        /**
         *
         * @param cid 公司id
         * @param service 数据库所属的服务名称
         * @param db 数据库名称
         * @param account 账号
         * @param pwd 密码
         * @param readConnNum 读连接数
         * @param writeConnNum 写连接数
         * @param dbUrl 数据库URL
         */
        public MysqlBuilder(int cid, String service, String db,
                String account, String pwd,
                int writeConnNum, int readConnNum, String dbUrl) {
            super(cid, service, db, account, pwd, writeConnNum, readConnNum, dbUrl);
        }

        @Override
        public MysqlWorker build(int dbNo) {
            try {
                return new MysqlWorker(this);
            } catch(Exception e) {
                LOG.error("Fail to create db connection({}.{}.{},cid:{}) to {}", service, db, dbNo, cid, dbUrl, e);
                return null;
            }
        }
    }
}
