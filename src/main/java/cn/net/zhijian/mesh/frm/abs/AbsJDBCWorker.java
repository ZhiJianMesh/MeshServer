package cn.net.zhijian.mesh.frm.abs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import cn.net.zhijian.util.LogUtil;

/**
 * 远程jdbc数据库抽象类
 */
public abstract class AbsJDBCWorker extends AbsRDBWorker {
    private static final Logger LOG = LogUtil.getInstance();


    public AbsJDBCWorker(AbsRDBWBuilder builder) {
        super(builder.cid, builder.service, builder.db,
             builder.longToStr(), builder.shardingStart(), builder.shardingEnd());
    }

    @Override
    public void executeRawDDL(AbsConnection conn, String sql) throws SQLException {
        if(LOG.isDebugEnabled()) {
            LOG.debug("{}-{}.executeRawDDL(`{}`)", service, dbName, sql);
        }
        Pattern alterTabPattern = Pattern.compile("^alter table .+ add column if not exists .+$");
        String s = sql.substring(0, 3).toLowerCase();
        boolean executed = false;

        if(s.equals("alt")) { //大部分不满足这条，在此结束，避免正则判断
            String lowSql = sql.toLowerCase();
            String blankSql = lowSql.replaceAll("\\s+", " ");
            if(alterTabPattern.matcher(blankSql).matches()) {
                executed = addColumnIfNotExists(conn, sql);
            }
        }
        
        if(!executed) {
            try(Statement stmt = ((Connection)conn.get()).createStatement()) {
                stmt.executeUpdate(sql); //建表语言不可以有事务控制
            }
        }
    }

    @Override
    public int executeRawDML(AbsConnection conn, String sql) throws SQLException {
        if(LOG.isDebugEnabled()) {
            LOG.debug("{}-{}.executeRawDML(`{}`)", service, dbName, sql);
        }

        //synchronized(conn) {//conn本身是单例，一个线程获取后，其他线程无法获取
            try(Statement stmt = ((Connection)conn.get()).createStatement()) {
                return stmt.executeUpdate(sql);
            }
        //}
    }


    /**
     * 执行多个写操作时，需要先启动事务，
     * 因为sqlite只有一个写链接，所以需要互斥
     */
    @Override
    public void beginTransaction(AbsConnection conn) {
        try {
            ((Connection)conn.get()).setAutoCommit(false);
        } catch (SQLException e) {
            LOG.error("Fail to begin transaction in {}.{}", service, dbName, e);
        }
    }
    
    @Override
    public void endTransaction(AbsConnection conn, boolean execOk) {
        try {
            if(execOk) {
                ((Connection)conn.get()).commit();
            } else {
                ((Connection)conn.get()).rollback();
            }
        } catch (SQLException e) {
            LOG.error("Fail to end transaction in {}.{}, result:{}", service, dbName, execOk, e);
        }
    }

    /**
     * 查询结果集有多行，每行不携带列名称
     * @param conn 虚拟连接
     * @param sql 数据库脚本
     * @return 多行数据
     * @throws SQLException 数据库异常
     */
    @Override
    public List<Object[]> queryArrays(AbsConnection conn, String sql) throws SQLException {
        JDBCResultSet resultSet = new JDBCResultSet();
        List<Object[]> rows = new ArrayList<>();

        try (PreparedStatement stmt = ((Connection)conn.get()).prepareStatement(sql);
            ResultSet rs = stmt.executeQuery()) {
            if(!rs.next()) { //必须在取meta之前，调用一次next，否则抛异常
                return rows; //无记录
            }
            ColumnMeta[] metas = parseMetas(rs.getMetaData());
            resultSet.setResultSet(rs);
            do {
                Object[] row = new Object[metas.length];
                for (int i = 0; i < metas.length; i++) {  //zero based
                    row[i] = metas[i].get(resultSet, longToStr);
                }
                rows.add(row);
            } while (rs.next());
        }
        
        return rows;
    }
    
    /**
     * 查询结果集有多行，最后一行携带列名称，在未知结果集数量时，应该默认为多行
     * @param conn 虚拟连接
     * @param sql 数据库脚本
     * @return 查询结果，其中最后一行是列名称，列名称与列一一对应
     * @throws SQLException 数据库异常
     */
    @Override
    public List<Object[]> queryArraysWithMetaTail(AbsConnection conn, String sql) throws SQLException {
        JDBCResultSet resultSet = new JDBCResultSet();
        List<Object[]> rows = new ArrayList<>();

        try (PreparedStatement stmt = ((Connection)conn.get()).prepareStatement(sql);
            ResultSet rs = stmt.executeQuery()) {
            if(!rs.next()) { //必须在取meta之前，调用一次next，否则抛异常
                return rows; //无记录
            }

            ColumnMeta[] metas = parseMetas(rs.getMetaData());
            Object[] colMetas = new Object[metas.length];
            for (int i = 0; i < metas.length; i++) {  //zero based
                colMetas[i] = metas[i].name;
            }
            resultSet.setResultSet(rs);

            do {
                Object[] row = new Object[metas.length];
                for (int i = 0; i < metas.length; i++) {  //zero based
                    row[i] = metas[i].get(resultSet, longToStr);
                }
                rows.add(row);
            } while (rs.next());
            rows.add(colMetas);
        }
        
        return rows;
    }
    
    /**
     * 查询结果集有多行，但是每行只有一列，结果集不携带列名称
     * @param conn 虚拟连接
     * @param sql 数据库脚本
     * @return 单列结果
     * @throws SQLException 数据库异常
     */
    @Override
    public List<Object> querySingles(AbsConnection conn, String sql) throws SQLException {
        JDBCResultSet resultSet = new JDBCResultSet();
        List<Object> rows = new ArrayList<>();

        try (PreparedStatement stmt = ((Connection)conn.get()).prepareStatement(sql);
            ResultSet rs = stmt.executeQuery()) {
            if(!rs.next()) { //必须在取meta之前，调用一次next，否则抛异常
                return rows; //无记录
            }
            
            ColumnMeta[] metas = parseMetas(rs.getMetaData());
            resultSet.setResultSet(rs);
            do {
                rows.add(metas[0].get(resultSet, longToStr)); //只取第一列的值
            } while (rs.next());
        }
        return rows;
    }
    
    /**
     * 查询结果集多行，且每行都携带列名，用一个map存储
     * @param sql 数据库脚本
     * @return 多行结果集
     */
    @Override
    public List<Map<String, Object>> queryMaps(AbsConnection conn, String sql) throws SQLException {
        JDBCResultSet resultSet = new JDBCResultSet();
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement stmt = ((Connection)conn.get()).prepareStatement(sql);
            ResultSet rs = stmt.executeQuery()) {
            if(!rs.next()) { //必须在取meta之前，调用一次next，否则抛异常
                return rows; //无记录
            }
            ColumnMeta[] metas = parseMetas(rs.getMetaData());
            resultSet.setResultSet(rs);
            do {
                Map<String, Object> row = new HashMap<>(metas.length);
                for (ColumnMeta meta : metas) { //zero based
                    row.put(meta.name, meta.get(resultSet, longToStr));
                }
                rows.add(row);
            } while (rs.next());
        }
        return rows;
    }
    
    @Override
    public Map<String, Object> queryMap(AbsConnection conn, String sql) throws SQLException {
        JDBCResultSet resultSet = new JDBCResultSet();
        try (PreparedStatement stmt = ((Connection)conn.get()).prepareStatement(sql);
            ResultSet rs = stmt.executeQuery()) {
            /*
             * 如果sql中出现了max、sum等函数，即使没有结果，
             * 这里也会莫名其妙地出现一行空记录
             */
            if(!rs.next()) { //必须在取meta之前，调用一次next，否则抛异常
                return null; //无记录
            }
            ColumnMeta[] metas = parseMetas(rs.getMetaData());
            resultSet.setResultSet(rs);
            Map<String, Object> line = new HashMap<>(metas.length);
            boolean isNull = true; //sqlite在无记录时仍然返回一个全空的行
            for (ColumnMeta meta : metas) {
                //单行，必须取meta信息
                Object o = meta.get(resultSet, longToStr);
                if(o != null) {
                    isNull = false;
                }
                line.put(meta.name, o);
            }

            return isNull ? null : line;
        }
    }

    /**
     * 结果集只有一行的查询，以list方式返回，无列名称
     * @param sql 脚本
     * @return 单行结果
     * @throws SQLException  数据库异常
     */
    @Override
    public Object[] queryLine(AbsConnection conn, String sql) throws SQLException {
        JDBCResultSet resultSet = new JDBCResultSet();
        
        try (PreparedStatement stmt = ((Connection)conn.get()).prepareStatement(sql);
            ResultSet rs = stmt.executeQuery()) {
            if(!rs.next()) { //必须在取meta之前，调用一次next，否则抛异常
                return null;
            }
            ColumnMeta[] metas = parseMetas(rs.getMetaData());
            resultSet.setResultSet(rs);
            Object[] line = new Object[metas.length];
            boolean isNull = true;
            for(int i = 0; i < metas.length; i++) { //zero based
                Object o = metas[i].get(resultSet, longToStr);
                if(o != null) { //在没有记录时仍然返回一个全空的行
                    isNull = false;
                }
                line[i] = o;
            }
            return isNull ? null : line;
        }
    }

    private static ColumnMeta[] parseMetas(ResultSetMetaData rm) throws SQLException {
        int num = rm.getColumnCount();
        ColumnMeta[] metas = new ColumnMeta[num];
        for(int i = 1; i <= num; i++) { //jdbc列号从1开始
            metas[i - 1] = new ColumnMeta(rm.getColumnName(i), rm.getColumnType(i), i);
        }
        return metas;
    }
    /**
     * SQLite数据库构造器
     * @author flyinmind of csdn.net
     *
     */
    public static abstract class AbsJDBCBuilder extends AbsRDBWBuilder {
        public final String dbUrl; //jdbc url
        public final String account;
        public final String password;

        /**
         * 
         * @param si 运行于其中的服务
         * @param service 数据库所属的服务名称
         * @param db 数据库名称
         * @param root 数据库运行的根路径
         */
        public AbsJDBCBuilder(int cid, String service, String db,
                String account, String pwd,
                int readConnNum, int writeConnNum, String dbUrl) {
            super(cid, service, db, readConnNum, writeConnNum);
            this.dbUrl = dbUrl;
            this.account = account;
            this.password = pwd;
        }
    }
    
    protected static class JDBCConnection extends AbsConnection {
        public JDBCConnection(Connection conn) {
            super(conn);
        }

        @Override
        public boolean closed() {
            try {
                return ((Connection)conn).isClosed();
            } catch (SQLException e) {
                return true;
            }
        }

        @Override
        public boolean test() {
            return true;
        }
    }
    
    public static class JDBCResultSet implements IResultSet {
        private ResultSet rs;
        
        public void setResultSet(ResultSet rs) {
            this.rs = rs;
        }
        
        public Object get(int type, int no, boolean longToStr) throws SQLException {
            switch(type) {
            case Types.VARCHAR: return rs.getString(no);
            case Types.INTEGER: return rs.getInt(no);
            case Types.BIGINT: {
                long v = rs.getLong(no);
                if(longToStr) {
                    /*
                     * js中long有精度损失问题，只能准确表达2^52(4503599627370496)以内的长整型数值，
                     * 超过此值，则会将末尾的n位变成0，
                     * 因为js中只有double型数值，最高位为符号位，后面11位是指数位，再后面的52位是尾数位。
                     * 所以一旦大于2^52，就会出现精度损失
                     */
                    if(v > JS_MIN_SAFE_LONG && v < JS_MAX_SAFE_LONG) {
                        return v;
                    }
                    return Long.toString(v);
                }
                return v;
            }
            case Types.DOUBLE: return rs.getDouble(no);
            case Types.FLOAT: return rs.getFloat(no);
            default: return rs.getObject(no);
            }
        }
    }

}
