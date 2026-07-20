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
                MysqlWorker mw = new MysqlWorker(this);
                //禁用反斜杠\作为字符串中的转义字符，因为系统使用的防注入没有考虑这种语法
                mw.executeRawDML("SET sql_mode = CONCAT_WS(',', @@sql_mode, 'NO_BACKSLASH_ESCAPES')");
                return mw;
            } catch(Exception e) {
                LOG.error("Fail to create db connection({}.{}.{},cid:{}) to {}", service, db, dbNo, cid, dbUrl, e);
                return null;
            }
        }
    }
}
