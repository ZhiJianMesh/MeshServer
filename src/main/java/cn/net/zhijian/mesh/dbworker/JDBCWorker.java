package cn.net.zhijian.mesh.dbworker;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.slf4j.Logger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.frm.abs.AbsConnection;
import cn.net.zhijian.mesh.frm.abs.AbsJDBCWorker;
import cn.net.zhijian.util.LogUtil;

/**
 * 驱动符合jdbc标准的数据库工作类
 */
public class JDBCWorker extends AbsJDBCWorker {
    private static final Logger LOG = LogUtil.getInstance();
    protected HikariDataSource dataSource;
    protected final AbsJDBCBuilder builder;
    
    public JDBCWorker(JDBCBuilder builder) {
        super(builder);
        this.builder = builder;
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(builder.dbUrl);
        hikariConfig.setUsername(builder.account);
        hikariConfig.setPassword(builder.password);
        hikariConfig.setMaximumPoolSize(builder.readConnNum + builder.writeConnNum);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        this.dataSource = new HikariDataSource(hikariConfig);
    }

    @Override
    public AbsConnection getWriteConn() throws MeshException {
        Connection conn;
        try {
            conn = dataSource.getConnection();
            return new JDBCConnection(conn);
        } catch (SQLException e) {
            throw new MeshException("Fail to getWriteConn", e);
        }
    }

    @Override
    public AbsConnection getReadConn() throws MeshException {
        try {
            Connection conn = dataSource.getConnection();
            return new JDBCConnection(conn);
        } catch (SQLException e) {
            throw new MeshException("Fail to getReadConn", e);
        }
    }

    @Override
    public void close() throws IOException {
        dataSource.close();
    }

    @Override
    protected boolean addColumnIfNotExists(AbsConnection conn, String sql) {
        try(Statement stmt = ((Connection)conn.get()).createStatement()) {
            stmt.executeUpdate(sql);
            return true;
        } catch (SQLException e) {
            LOG.error("Fail to execute addColumnIfNotExists({}) ", sql, e);
            return false;
        }
    }

    /**
     * 通用 JDBC 数据库构造器
     * @author flyinmind of csdn.net
     *
     */
    public static class JDBCBuilder extends AbsJDBCBuilder {
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
        public JDBCBuilder(int cid, String service, String db,
                String account, String pwd,
                int readConnNum, int writeConnNum, String dbUrl) {
            super(cid, service, db, account, pwd, readConnNum, writeConnNum, dbUrl);
        }

        @Override
        public JDBCWorker build(int dbNo) {
            try {
                return new JDBCWorker(this);
            } catch(Exception e) {
                LOG.error("Fail to create db connection({}.{}.{},cid:{}) to {}", service, db, dbNo, cid, dbUrl, e);
                return null;
            }
        }
    }

    @Override
    public void sync(List<String> sqls) {
        //标准数据库无需webdb实现同步
    }

    @Override
    public void sync(String sql) {
        //标准数据库无需webdb实现同步
    }

    @Override
    public boolean remove() {
        return true;
    }
}
