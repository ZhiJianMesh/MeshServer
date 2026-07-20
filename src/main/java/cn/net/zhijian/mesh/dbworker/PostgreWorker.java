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
                PostgreWorker pw = new PostgreWorker(this);
                //禁用反斜杠\作为字符串中的转义字符，因为系统使用的防注入没有考虑这种语法
                pw.executeRawDML("SET standard_conforming_strings = off;");
                return pw;
            } catch(Exception e) {
                LOG.error("Fail to create db connection({}.{}.{},cid:{}) to {}", service, db, dbNo, cid, dbUrl, e);
                return null;
            }
        }
    }
}
