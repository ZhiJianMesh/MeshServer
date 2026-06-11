package cn.net.zhijian.mesh.dbworker;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.frm.abs.AbsConnection;
import cn.net.zhijian.util.LogUtil;

public class OracleWorker extends JDBCWorker {
    private static final Logger LOG = LogUtil.getInstance();
    
    public OracleWorker(OracleBuilder builder) {
        super(builder);
    }
    
    @Override
    protected boolean addColumnIfNotExists(AbsConnection conn, String sql) {
        //ALTER TABLE table_name ADD COLUMN IF NOT EXISTS column_name data_type;
        String lowSql = sql.toLowerCase();
        String blankSql = lowSql.replaceAll("\\s+", " ");
        
        int pTable = blankSql.indexOf("table") + 5/*table.length*/ + 1;
        int pAdd = blankSql.indexOf(' ', pTable);
        
        String table = blankSql.substring(pTable, pAdd).trim();
        //lowSql中空格分布与sql相同，所以选择的exists位置与sql也相同
        //不能用blankSql查找，因为其中的一个或连续的多个tab、换行符都被替换成了一个空格
        int pExists = lowSql.indexOf("exists", pAdd) + 6/*exists.length*/ + 1;

        pExists = blankSql.indexOf("exists", pAdd) + 6/*exists.length*/ + 1;
        int p = blankSql.indexOf(' ', pExists); //exists后面跟着的是表名
        String column = blankSql.substring(pExists, p).trim();
        
        try(Statement stmt = ((Connection)conn.get()).createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) INTO num FROM USER_TAB_COLUMNS WHERE TABLE_NAME='"
                    +table+"' AND COLUMN_NAME='" + column + "'")) {
            if(rs.next()) { //num
                int exists = rs.getInt(1);
                if(exists > 0) {
                    return true; //字段已存在，不必执行
                }
            }
            String alterSql = "alter table " + table + " add column " + sql.substring(pExists);
            stmt.executeUpdate(alterSql); //字段不存在，在此函数中执行alterSql
            return true;
        } catch (SQLException e) {
            LOG.error("Fail to get table({}) columns", table, e);
            return false;
        }
    }
    
    /**
     * Mysql数据库构造器
     * @author flyinmind of csdn.net
     *
     */
    public static class OracleBuilder extends JDBCBuilder {
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
        public OracleBuilder(int cid, String service, String db,
                String account, String pwd,
                int writeConnNum, int readConnNum, String dbUrl) {
            super(cid, service, db, account, pwd, writeConnNum, readConnNum, dbUrl);
        }

        @Override
        public OracleWorker build(int dbNo) {
            try {
                return new OracleWorker(this);
            } catch(Exception e) {
                LOG.error("Fail to create db connection({}.{}.{},cid:{}) to {}", service, db, dbNo, cid, dbUrl, e);
                return null;
            }
        }
    }
}
