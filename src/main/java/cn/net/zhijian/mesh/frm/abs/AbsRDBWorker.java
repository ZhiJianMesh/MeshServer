package cn.net.zhijian.mesh.frm.abs;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.js.JsEngine;
import cn.net.zhijian.util.Calculator;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 数据库工人抽象类，实现基本的数据库操作，比如初始化、增删改查等，
 * 实际的数据库操作，需要在子类中，根据数据库类型不同提供不同的实现
 * @author flyinmind of csdn.net
 *
 */
public abstract class AbsRDBWorker extends AbsDBWorker {
    private static final Logger LOG = LogUtil.getInstance();
    public static final String DATABASE_DIR = "dbs";
   
    public static final String INIT_VER = "0.0.0";
    public static final String SYSTEM_TABLE = "db_system_cfg";
    
    private static final String SYSCFG_RDBVER = "ver";
    private static final String SYSCFG_UPTIME = "uptime";

    public static final String HANDLE_RESULT = "_result";

    private static final List<Integer> IgnoresAll = Arrays.asList(-1);

    private static final String SQL_SFROM = "from";
    private static final char[] SQL_INTO = "into".toCharArray();
    private static final char[] SQL_WHERE = "where".toCharArray();
    //从树状结构中查询所有子节点以及子节点的子节点，格式为select subsof(xxx) from tbl
    //表结构中必须有id与fid字段
    private static final char[] SQL_SUBSOF = "subsof(".toCharArray();
    private static final char[] SQL_VALUES = "values".toCharArray();
    private static final char[] SQL_SELECT = "select".toCharArray();
    private static final char[] SQL_FROM = SQL_SFROM.toCharArray();
    private static final char[] SQL_SEPARATOR = ";".toCharArray();
    private static final char[] SQL_UPDATE_TIME = SEG_UPDATETIME.toCharArray();
    private static final char[] SQL_BRACKETS = new char[] {SQL_QUOTE, SQL_QUOTE, '(', ')'};
    private static final char[] SQL_QUOTATIONS = new char[] {SQL_QUOTE, SQL_QUOTE};
    private static final char[] SQL_INSERTIGNORE = "or ignore".toCharArray();
    private static final String SQL_SINSERTIGNORE = " or ignore ";

    private static final ReentrantLock InstanceLock = new ReentrantLock();
    protected static final Map<String, AbsRDBWorker> DBWorkers = new ConcurrentHashMap<>();

    public enum State {NOT_INIT, NORMAL, READONLY, CLOSING, CLOSED}

    protected volatile State state = State.NOT_INIT;
    protected String ver = INIT_VER; //初始版本
    protected final int shardStart;
    protected final int shardEnd;

    public final int cid;
    public final String service; //实例所属的服务，与dbName唯一确定一个实例
    public final String dbName;
    protected final boolean longToStr; //是否将long转为字符串，解决js中long精度损失问题

    public abstract AbsConnection getWriteConn() throws MeshException;
    public abstract AbsConnection getReadConn() throws MeshException;
    /**
     * 执行DDL(Data Definition Language)，比如create table、create index等，
     * 不会对sql做任何修改，也不会对其执行sync，且不能在事务中运行
     * @param conn 虚拟连接
     * @param sql 待执行sql
     * @throws SQLException 数据库异常
     */
    public abstract void executeRawDDL(AbsConnection conn, String sql) throws SQLException;

    /**
     * 执行DML(Data Manipulation Language)，比如update、insert、delete等，
     * 不会对sql做任何修改，也不会对其执行sync
     * @param conn 虚拟连接
     * @param sql 待执行sql，只能一条
     * @return 增删改sql影响到的行数
     * @throws SQLException 数据库异常
     */
    public abstract int executeRawDML(AbsConnection conn, String sql) throws SQLException;
    
    /**
     * 启动事务，只有写操作时才需要
     * @param conn 虚拟连接
     */
    public abstract void beginTransaction(AbsConnection conn);
    
    /**
     * 结束事务，如果执行成功，并提交事务
     * @param conn 虚拟连接
     * @param execOk 是否成功，如果false会回滚
     */
    public abstract void endTransaction(AbsConnection conn, boolean execOk);
    
    /**
     * 查询结果集有多行，在未知结果集数量时，应该默认为多行
     * @param conn 虚拟连接
     * @param sql 数据库脚本
     * @return 查询结果
     * @throws SQLException 数据库异常
     */
    public abstract List<Object[]> queryArrays(AbsConnection conn, String sql) throws SQLException;

    /**
     * 查询结果集有多行，最后一行携带列名称，在未知结果集数量时，应该默认为多行
     * @param conn 虚拟连接
     * @param sql 数据库脚本
     * @return 查询结果，其中最后一行是列名称，列名称与列一一对应
     * @throws SQLException 数据库异常
     */
    public abstract List<Object[]> queryArraysWithMetaTail(AbsConnection conn, String sql) throws SQLException;

    /**
     * @param conn 虚拟连接
     * @param sql 数据库脚本
     * @return 以map方式返回的行数据
     */
    public abstract Map<String, Object> queryMap(AbsConnection conn, String sql) throws SQLException;

    /**
     * 查询结果集多行，且每行都用一个map表示
     * @param conn 虚拟连接
     * @param sql 数据库脚本
     * @return 多行结果，每行对应一个map
     */
    public abstract List<Map<String, Object>> queryMaps(AbsConnection conn, String sql) throws SQLException;

    /**
     * 查询结果集有多行，但是每行只有一列，与queryLine功能类似
     * @param conn 虚拟连接
     * @param sql 数据库脚本
     * @return 单行数据，以list方式返回
     * @throws SQLException 数据库异常
     */
    public abstract List<Object> querySingles(AbsConnection conn, String sql) throws SQLException;

    /**
     * 结果集只有一行的查询，以list方式返回，无列名称
     * @param conn 虚拟连接
     * @param sql 数据库脚本
     * @return 行数据，以数组方式返回
     * @throws SQLException 数据库异常
     */
    public abstract Object[] queryLine(AbsConnection conn, String sql) throws SQLException;
    
    /**
     * 解决调整表结构时语法兼容问题
     * 如果字段存在，则不添加
     * 如果所用数据库不能支持此语法，需要重载此函数
     * @param conn 连接
     * @param sql 调整表结构的sql
     * @return 调整结果
     */
    protected abstract boolean addColumnIfNotExists(AbsConnection conn, String sql);

    /**
     * 删除数据库实例，必须先关闭所有数据库链接
     * @return 删除数据库实例
     */
    public abstract boolean remove();

    /**
     * 同步数据
     * <p>
     * 如果数据库本身具有同步能力，或者不作为服务端使用，可以不实现此方法，比如mysql等。
     * mysql的binlog同步方式有Statement、Row和Mixed，
     * 本系统采用的复制方式类似Statement，只记录sql语句，
     * 所以sql中不要使用时间戳、uuid等在同步到目的节点执行时会发生变化的内置函数，
     * 碰到这种需求时，请使用内置占位符@{NOW}、@{UUID}等。
     * @param sqls 写脚本，不可存在读操作
     */
    public abstract void sync(List<String> sqls);

    public abstract void sync(String sql);
    
    public AbsRDBWorker(int cid, String service, String db, boolean longToStr,
            int shardingStart, int shardingEnd) {
        this.service = service;
        this.dbName = db;
        this.longToStr = longToStr;
        this.cid = cid;
        this.shardStart = shardingStart;
        this.shardEnd = shardingEnd;
    }

    /**
     * 数据库修改tabledef或增加节点时，调用一批sql对其进行初始化或升级，
     * 升级之后，ver会记录在SYSTEM_TABLE表中，凡是版本号小于本系统版本的初始化，是不会执行的
     * @param sqls 待执行的初始化sql
     * @param toVer 目标版本号
     * @return 是否初始化成功
     */
    private boolean initDB(List<Object> sqls, String toVer, boolean needModify) {
        if(this.state != State.NORMAL) {
            LOG.error("Call initDB on an abnormal db {}.{}", service, dbName);
            return false;
        }

        boolean execOk = true;
        String sql = IConst.EMPTY_STR;
        String now = Long.toString(System.currentTimeMillis());
        List<String> rawSqls = new ArrayList<>(sqls.size());
        
        try(AbsConnection conn = getWriteConn()) {
            //sqlite/mysql等，ddl语句不支持事务，所以不起事务
            for(Object o : sqls) {
                sql = ValParser.parseString(o, IConst.EMPTY_STR).trim();
                if(StringUtil.isEmpty(sql)) {
                    LOG.error("Invalid initDB sql {}", sql);
                    continue;
                }
                
                if(LOG.isDebugEnabled()) {
                    LOG.debug("{}.{}:{}", service, dbName, sql);
                }

                switch(getSqlType(sql)) {
                case CREATE:
                case ALTER:
                case DROP:
                    if(needModify) {
                        String s = modifyDDLSql(sql);
                        if(LOG.isDebugEnabled()) {
                            LOG.debug("Modified ddl sql:{} in {}.{}", s, service, dbName);
                        }
                        executeRawDDL(conn, s);
                        rawSqls.add(s);
                    } else {
                        executeRawDDL(conn, sql);
                        rawSqls.add(sql);
                    }
                    break;
                default:
                    if(needModify) {
                        String s = modifyDMLSql(sql, now);
                        if(LOG.isDebugEnabled()) {
                            LOG.debug("Modified dml sql:{} in {}.{}", s, service, dbName);
                        }
                        executeRawDML(conn, s);
                        rawSqls.add(s);
                    } else {
                        executeRawDML(conn, sql);
                        rawSqls.add(sql);
                    }
                    break;
                }
            }
        } catch (SQLException | MeshException e) {
            LOG.error("Fail to execute `{}` in `{}`", sql, this, e);
            execOk = false;
        } finally {
            if(execOk) {
                this.state = State.NORMAL;
                this.ver = toVer;
                innerSetSysConfig(SYSCFG_RDBVER, toVer);
            }
            sync(rawSqls); //无论是否成功，都同步
        }
        
        return execOk;
    }
    
    /**
     * 运行一个版本的初始化或升级脚本
     * @param sqlList 脚本[{minVer:"xx", "maxVer":"yy",toVer:"zz",sqls:[sql1,sql2...]}...]，
     *     第一个是最低版本，第二个是较高版本，以此类推，最后一个是最高版本需要执行的脚本
     * @param version 升级后的版本
     * @return 成功则返回true，否则返回false
     */
    public boolean execInitDDLs(List<Object> sqlList, String version) {
        for(Object o : sqlList) {
            Map<String, Object> oneVer = ValParser.parseObject(o);
            String curVer = version();
            String minVer = ValParser.getAsStr(oneVer, "minVer", curVer); //可升级的最小版本号
            String maxVer = ValParser.getAsStr(oneVer, "maxVer", curVer); //可升级的最大版本号

            String toVer = ValParser.getAsStr(oneVer, "toVer", version); //升级到的版本号，默认一步到位
            List<Object> sqls = ValParser.getAsList(oneVer, "sqls");
            /*
             * 配置时，第一个必须是初始化脚本，后面的才是各个版本的升级脚本，版本逐次升高。
             * [{"minVer":"min1", "maxVer":"max1","toVer1":"to",建表语句},
             * {"minVer":"min2", "maxVer":"max2","toVer2":"to",针对v1的脚本},
             * {"minVer":"min3", "maxVer":"max3","toVer3":"to",针对v2的脚本}...]
             * 所有脚本执行是累加的，直到最后一个升级成功，每执行一次db的版本升高一次。
             * 比如当前版本是v1，则从v1一直往后执行，直到最后一个。
             */
            if(StringUtil.compare(curVer, minVer, '.') < 0 //本地版本在最小(包括)、最大版本(不包括)之间才会执行升级
               || StringUtil.compare(curVer, maxVer, '.') > 0) {
                LOG.debug("{}.{}:curent version `{}` is not >={} and <={}, do nothing",
                        service, dbName, curVer, minVer, maxVer);
                continue;
            }

            boolean needModify = ValParser.getAsBool(oneVer, "needModify", true);
            if(!initDB(sqls, toVer, needModify)) {
                LOG.error("Fail to execute init from {} to {} in `{}`", curVer, toVer, this);
                return false;
            }
            LOG.debug("Update database version of `{}` from {} to {}", this, curVer, toVer);
        }
        return true;
    }
    
    /**
     * 初始化系统表，必须在数据库创建后第一个被执行
     */
    protected void initSysTables() {
        String createTabSql = "create table if not exists " + SYSTEM_TABLE
                + "(name     varchar(255) not null primary key,"
                + "val      text not null)";
        try (AbsConnection conn = getWriteConn()){
            executeRawDDL(conn, createTabSql);
            sync(createTabSql);
        } catch(SQLException | MeshException e) {
            LOG.error("Fail to create table {}", SYSTEM_TABLE, e);
        }

        String v = getSysConfig(SYSCFG_RDBVER);
        this.ver = StringUtil.isEmpty(v) ? INIT_VER : v;
        long uptime = System.currentTimeMillis();
        String dt = DateUtil.utcToLocale(uptime, "yyyy-MM-dd HH:mm:ss");
        innerSetSysConfig(SYSCFG_UPTIME, dt);
        LOG.debug("db {}.{},ver:{}, uptime:{}", service, dbName, ver, dt);
    }
    
    public String getSysConfig(String name) {
        if(!name.matches("\\w{1,30}")) {
            return IConst.EMPTY_STR;
        }

        try (AbsConnection conn = getReadConn()){
            Object[] res = queryLine(conn, "select val from " + SYSTEM_TABLE + " where name='" + name + "'");
            if(res == null) {
                return IConst.EMPTY_STR;
            }
            return ValParser.parseString(res[0]);
        } catch (SQLException | MeshException e) {
            LOG.error("Fail to get sys config item {} in {}.{}", name, service, dbName, e);
            return IConst.EMPTY_STR;
        }
    }

    /**
     * 在数据库系统表中设置配置项，比如启动时间
     * @param name 系统配置名称
     * @param val 值
     * @return 参数异常或插入失败，则返回非RetCode.OK
     */
    public int setSysConfig(String name, String val) {
        if(!name.matches("\\w{1,30}")
          || name.equals(SYSCFG_RDBVER) || name.equals(SYSCFG_UPTIME)) {
            LOG.error("Invalid sys config item {}", name);
            return RetCode.WRONG_PARAMETER;
        }

        return innerSetSysConfig(name, val);
    }

    private int innerSetSysConfig(String name, String val) {
        String sql = "replace into " + SYSTEM_TABLE + "(name,val) values('" + name
                + "','" + val.replace("'", "''") + "')";
        try(AbsConnection conn = getWriteConn()) {
            executeRawDML(conn, sql);
            sync(sql);
            return RetCode.OK;
        } catch (SQLException | MeshException e) {
            LOG.error("Fail to execute '{}' in `{}`", sql, this, e);
        }
        return RetCode.DB_ERROR;
    }

    public final State state() {
        return state;
    }
    
    public final boolean normal() {
        return state == State.NORMAL || state == State.READONLY;
    }
    
    protected final void state(State state) {
        this.state = state;
    }

    public final void setWritable(boolean canWrite) {
        if(canWrite) {
            state = State.NORMAL;
        } else {
            state = State.READONLY;
        }
        LOG.debug("Set writable to {}", state.name());
    }

    public final boolean writable() {
        return state == State.NORMAL;
    }
    
    @Override
    public final String name() {
        return dbName;
    }
    //-------------------------------------------------------------------------
    /**
     * 执行数据库定义sql，比如create table、index等，
     * ddl语句不支持事务，无论是否成功都同步
     * @param conn 虚拟连接
     * @param ddlSqls 数据库定义脚本
     * @throws SQLException 数据库异常
     * @return 执行成功的行数
     */
    public int executeDDLSqls(AbsConnection conn, String[] ddlSqls) throws SQLException {
        List<String> sqls = new ArrayList<>(ddlSqls.length);
        int n = 0;
        String errSql = null;

        try {
            for(String sql : ddlSqls) {
                String s = modifyDDLSql(sql);
                if(LOG.isDebugEnabled()) {
                    LOG.debug("Modified sql:`{}` in {}.{}", service, dbName, s);
                }
                errSql = s;
                executeRawDDL(conn, s);
                sqls.add(s);
            }
        } catch (SQLException e) {
            LOG.error("Fail to execute `{}` in `{}`", errSql, this, e);
            throw e;
        } finally {
            sync(sqls);
        }

        return n;
    }

    /**
     * 在服务的`/dbs/服务名/`下创建一个RDB数据库
     * @param service 创建db的服务名称
     * @param dbCfg 数据库表配置(name,type,version,versions)
     * @return 创建结果,true为成功
     */
    public boolean createRDB(String service, Map<String, Object> dbCfg) {
        String version = ValParser.getAsStr(dbCfg, "version");
        List<Object> sqls = ValParser.getAsList(dbCfg, "versions");

        if(sqls != null && !sqls.isEmpty()) {
            if(!execInitDDLs(sqls, version)) {
                LOG.error("Fail to call init sqls of {}.{}", service, dbName);
                return false;
            }
        }
        return true;
    }
    
    /**
     * 查询多行，每行有多列，但是无列名称
     * @param sql 查询语句
     * @return 结果
     */
    public List<Object[]> queryArrays(String sql) {
        try(AbsConnection conn = getReadConn()){
            return queryArrays(conn, sql);
        } catch (SQLException | MeshException e) {
            LOG.error("Fail to query {} in `{}`", sql, this, e);
        }
        return new ArrayList<>();
    }
    
    public List<Object> querySingles(String sql) {
        try(AbsConnection conn = getReadConn()) {
            return querySingles(conn, sql);
        } catch (SQLException | MeshException e) {
            LOG.error("Fail to querySingles {} in `{}`", sql, this, e);
        }
        return new ArrayList<>();
    }

    /**
     * 查询多行，但是每行只有一列
     * @param conn 连接
     * @param sql 脚本，支持subsof函数
     * @return 结果集
     */
    public List<Object> querySingleList(AbsConnection conn, String sql) throws SQLException {
        int pos0 = findSqlKeyWord(sql, SQL_SUBSOF, SQL_SELECT.length + 1, SQL_QUOTATIONS);
        if(pos0 > 0) { //select subsof(id_value, id_name, fid_name, inits...) from table_name
            pos0 += SQL_SUBSOF.length;
            int pos1 = sql.indexOf(')', pos0);
            if(pos1 < 0) {
                throw new SQLException("invalid sql 'subsof', no ending");
            }
            String[] segs = sql.substring(pos0, pos1).trim().split(",");
            String id, idName, fidName;
            if(segs.length >= 3) {
                id = segs[0].trim();
                idName = segs[1].trim();
                fidName = segs[2].trim();
            } else if(segs.length == 1) {
                id = segs[0].trim();
                idName = "id";
                fidName = "fid";
            } else {
                throw new SQLException("invalid sql 'subsof',invalid parameters");
            }
            pos0 = sql.indexOf(SQL_SFROM, pos1 + 1);
            if(pos0 < 0) {
                throw new SQLException("invalid sql subsof,no from table_name");
            }
            String tab = sql.substring(pos0 + SQL_FROM.length).trim();
            Set<String> list = new HashSet<>();
            if(segs.length > 3) { //初始列表
                for(int i = 3; i < segs.length; i++) {
                    list.add(segs[i].trim());
                }
            }
            querySubs(conn, tab, list, id, idName, fidName);
            return new ArrayList<>(list);
        }
        return querySingles(conn, sql);
    }

    public List<Object> querySingleList(String sql) {
        try(AbsConnection conn = getReadConn()) {
            return querySingleList(conn, sql);
        } catch (SQLException | MeshException e) {
            LOG.error("Fail to querySingleList {} in `{}`", sql, this, e);
        }
        return new ArrayList<>();
    }
    
    private void querySubs(AbsConnection conn, String table, Set<String> list,
            String id, String idName, String fidName) throws SQLException {
        String sql = "select " + idName + " from " + table
                + " where " + fidName + "=" + id + " order by " + idName;
        List<Object> ids = querySingles(conn, sql);
        if(ids != null && !ids.isEmpty()) {
            querySubs(conn, table, list, ids, idName, fidName);
        }
    }
    
    private void querySubs(AbsConnection conn, String table, Set<String> list,
            List<Object> ids, String idName, String fidName) throws SQLException {
        for(Object i : ids) {
            String id = ValParser.parseString(i);
            if(list.contains(id)) { //环状，错误的，必须终止
                continue;
            }
            list.add(id);
            querySubs(conn, table, list, id, idName, fidName);
        }
    }
    
    public List<Map<String, Object>> queryMaps(String sql) throws SQLException, MeshException {
        try (AbsConnection conn = getReadConn()) {
            return queryMaps(conn, sql);
        }
    }
    
    public Map<String, Object> queryMap(String sql) throws SQLException, MeshException {
        try (AbsConnection conn = getReadConn()) {
            return queryMap(conn, sql);
        }
    }
    
    public Object[] queryLine(String sql) {
        try(AbsConnection conn = getReadConn()){
            return queryLine(conn, sql);
        } catch (SQLException | MeshException e) {
            LOG.error("Fail to query {} in `{}`", sql, this, e);
        }
        return null;
    }
    
    public int executeRawDML(String sql) {
        try(AbsConnection conn = getWriteConn()){
            return executeRawDML(conn, sql);
        } catch (SQLException | MeshException e) {
            LOG.error("Fail to execute {} in `{}`", sql, this, e);
        }
        return 0;
    }

    public List<Object[]> queryArraysWithMetaTail(String sql) {
        try(AbsConnection conn = getReadConn()){
            return queryArraysWithMetaTail(conn, sql);
        } catch (SQLException | MeshException e) {
            LOG.error("Fail to query {} in `{}`", sql, this, e);
        }
        return null;
    }
    //-------------------------------------------------------------------------
    @Override
    public String type() {
        return RDB;
    }
    
    @Override
    public String toString() {
        return service + '.' + dbName + '@' + cid
               + "(ver:" + ver + ",shard:" + shardStart + '-' + shardEnd + ')';
    }
    
    /**
     * 业务数据库的版本，注意：非数据库软件的版本
     * @return 版本号
     */
    public String version() {
        return ver;
    }

    public boolean isValidSharding(int sharding) {
        return (sharding >= shardStart && sharding < shardEnd);
    }

    /**
     * 数据库工人创建工厂接口
     * @author flyinmind of csdn.net
     */
    public abstract static class AbsRDBWBuilder {
        public final String service;//实例所属的服务
        public final String db;
        public final int cid; 
        
        protected boolean createIfAbsent = false; //如果不存在，则创建
        protected boolean longToStr = true; //长整型转为字符串，适应js中long越界的问题
        private NodeAddress[] slaves = null; //从节点
        private int shardingStart;
        private int shardingEnd;
        //不同数据库对读写连接的处理不相同，sqlite两者分开，mysql可以将两个加起来
        public final int writeConnNum;
        public final int readConnNum;
 
        public AbsRDBWBuilder(int cid, String service, String db, int writeConnNum, int readConnNum) {
            this.cid = cid;
            this.service = service;
            this.db = db;
            this.writeConnNum = writeConnNum;
            this.readConnNum = readConnNum;
        }
        
        public void createIfAbsent(boolean createIfAbsent) {
            this.createIfAbsent = createIfAbsent;
        }
        
        public void longToStr(boolean longToStr) {
            this.longToStr = longToStr;
        }
        
        public AbsRDBWBuilder slaves(NodeAddress[] slaves) {
            this.slaves = slaves;
            return this;
        }
        
        public boolean longToStr() {
            return longToStr;
        }
        
        public boolean createIfAbsent() {
            return createIfAbsent;
        }
        
        public NodeAddress[] slaves() {
            return slaves;
        }

        public void sharding(int start, int end) {
            this.shardingStart = start;
            this.shardingEnd = end;
        }
        
        public int shardingStart() {
            return shardingStart;
        }

        public int shardingEnd() {
            return shardingEnd;
        }
        
        /**
         * 创建一个dbworker，可以是TreeDBWorker也可以是SqliteWorker、SearchDBWorker
         * @param dbNo webdb编号
         * @return 创建的worker实例
         */
        public abstract AbsRDBWorker build(int dbNo);
    }
 
    //-------------------------------------------------------------------------
    public enum SqlType {INSERT, DELETE, UPDATE, REPLACE,
        SELECT, ERROR, CREATE, ALTER, DROP, SCRIPT, RUNTIMESCRIPT;
        public boolean laterModify() {
            return this == SCRIPT || this == RUNTIMESCRIPT; //脚本或拼接的sql需要后期编辑
        }
    }
    
    /**
     * 用于解决安卓与云侧数据库处理的不同
     * @author flyinmind of csdn.net
     */
    public interface IResultSet {
        long JS_MAX_SAFE_LONG = (1L << 52); //4503599627370496
        long JS_MIN_SAFE_LONG = -JS_MAX_SAFE_LONG;
        
        Object get(int type, int no, boolean longToStr) throws SQLException;
    }
    
    /**
     * 列的元数据
     * @author flyinmind of csdn.net
     *
     */
    public static class ColumnMeta {
        public final String name;
        public final int type;
        public final int colNo;

        public ColumnMeta(String name, int type, int colNo) {
            this.name = name;
            this.type = type;
            this.colNo = colNo;
        }
        
        public Object get(IResultSet rs, boolean longToStr) throws SQLException {
            return rs.get(type, colNo, longToStr);
        }
    }

    public static SqlType getSqlType(String sql) {
        //至少有select/insert/delete/update/replace/create/js:开头
        if(sql.length() <= 7) {
            return SqlType.ERROR;
        }
        String s = sql.substring(0, 3).toLowerCase();
        /*
         * 不支持select ...into...使用insert into...select...替代.
         * 升级脚本中可以使用以下语法创建临时表，然后调整表结构
         * CREATE TABLE newtable AS SELECT * FROM oldtable
         */
        if(s.equals("sel")) {
            if(findSqlKeyWord(sql, SQL_INTO, 7) > 0) {
                return SqlType.ERROR;
            }
            return SqlType.SELECT;
        }
        if(s.equals("ins")) {
            return SqlType.INSERT;
        }
        if(s.equals("del")) {
            return SqlType.DELETE;
        }
        if(s.equals("upd")) {
            return SqlType.UPDATE;
        }
        if(s.equals("rep")) {
            return SqlType.REPLACE;
        }
        if(s.equals("cre")) {
            return SqlType.CREATE;
        }
        if(s.equals("alt")) {
            return SqlType.ALTER;
        }
        if(s.equals("dro")) {
            return SqlType.DROP;
        }
        if(s.startsWith(JS_HEAD)) {
            return SqlType.SCRIPT;
        }
        if(s.startsWith(RS_HEAD)) {
            return SqlType.RUNTIMESCRIPT;
        }
        return SqlType.ERROR;
    }

    /**
     * 在sql中查找关键词的位置，一次只能寻找一个，且会跳过skipPairs括起的内容。
     * 查找时不区分大小写，要求kw必须小写
     * @param sql 脚本
     * @param kw 关键词列表，必须小写
     * @param start sql开始位置
     * @param skipPairs 需要忽略的部分，比如''、()括起的部分，成对出现，前一个字符是开始符，后一个字符是结束符
     * @return 第一个匹配上的关键词位置，没找到则返回-1
     */
    private static int findSqlKeyWord(String sql, char[] kw, int start, char[] skipPairs) {
        int len = sql.length();
        int skip = -1; //标识字符串引号运算符的开始
        int skipNum = skipPairs.length;
        char ch;
        int findPos = 0;
        int j;
        int level = 0;
        int kwLen = kw.length;

        for(int i = start; i < len; i++) {
            ch = sql.charAt(i);
            if(skip >= 0) { //已有开始符，找到跳过内容的结束位置
                if(ch == skipPairs[skip + 1]){//这一句放前面，解决头尾都是同一个字符的情况
                    level--;
                } else if(ch == skipPairs[skip]) {
                    level++; //比如()里面还可能出现()
                }
                if(level == 0) { //退出所有层之后才会结束
                    skip = -1;
                }
                continue;
            }
            
            for(j = 0; j < skipNum; j += 2) { //寻找跳过内容的开始
                if(ch == skipPairs[j]) {
                    findPos = 0;
                    skip = j;
                    level = 1;
                    break;
                }
            }
            if(skip >= 0) { //如果找到了，不必查找关键词
                continue;
            }

            if(Character.toLowerCase(ch) == kw[findPos]) {
                findPos++;
                if(findPos == kwLen) { //匹配到字符串了
                    return i - kwLen + 1;
                }
            } else {
                findPos = 0;
            }
        }
        return -1;
    }
    
    /**
     * 在sql中查找关键词的位置，一次只能寻找一个，会跳过字符串(单引号)中的内容。
     * 与findSqlKeyWord区别在于它只避开字符串。查找时不区分大小写，要求kw必须小写
     * @param sql 脚本
     * @param kw 关键词列表，必须小写
     * @param start sql开始位置
     * @return 第一个匹配上的关键词位置，没找到则返回-1
     */
    private static int quickFindSqlKw(String sql, char[] kw, int start) {
        int len = sql.length();
        boolean inStr = false; //标识字符串引号运算符的开始
        char ch;
        int findPos = 0;
        int kwLen = kw.length;

        for(int i = start; i < len; i++) {
            ch = sql.charAt(i);
            if(inStr) { //找到字符串的结束位置
                if(ch == SQL_QUOTE) {
                    inStr = false;
                }
                continue;
            }
            
            if(ch == SQL_QUOTE) { //如果碰到了字符串引号，不必查找关键词
                findPos = 0;
                inStr = true;
                continue;
            }

            if(Character.toLowerCase(ch) == kw[findPos]) {
                findPos++;
                if(findPos == kwLen) { //匹配到字符串了
                    return i - kwLen + 1;
                }
            } else {
                findPos = 0;
            }
        }
        return -1;
    }

    private static int findSqlKeyWord(String sql, char[] kw, int start) {
        return quickFindSqlKw(sql, kw, start);
    }
    
    private static int findSqlKeyWord(String sql, char[] kw) {
        return quickFindSqlKw(sql, kw, 0);
    }
    
    private static final char[] SCRIPT_STR_FLAGS = new char[] {SQL_QUOTE, '"', '`'};
    /**
     * 只要script中'/"/`括起来的内容中存在任意一个kw，则返回相应位置，不区分大小写，
     * 几个kw是同时查找的，这样可以提高效率
     * @param sql 数据库脚本
     * @param kwList 关键词列表，必须全部小写
     * @return 第一个匹配上的关键词的位置，都不存在则返回-1
     */
    public static int findSqlKeyWordsInScript(String sql, char[][] kwList) {
        int len = sql.length();
        int inStr = -1; //标识字符串引号运算符的开始
        char ch;
        int j;
        int keyNum = kwList.length;
        int[] matchedLen = new int[keyNum];

        for(int i = 0; i < len; i++) {
            ch = sql.charAt(i);
            if(inStr >= 0) {
                if(ch == SCRIPT_STR_FLAGS[inStr]) {
                    inStr = -1;
                    continue;
                }
                ch = Character.toLowerCase(ch);
                for(j = 0; j < keyNum; j++) {
                    if(ch == kwList[j][matchedLen[j]]) {
                        matchedLen[j]++;
                        if(matchedLen[j] == kwList[j].length) {
                            return i - kwList[j].length + 1;
                        }
                    } else {
                        matchedLen[j] = 0;
                    }
                }
            } else {
                if (ch == SCRIPT_STR_FLAGS[0]) {
                    inStr = 0;
                }else if (ch == SCRIPT_STR_FLAGS[1]) {
                    inStr = 1;
                }else if (ch == SCRIPT_STR_FLAGS[2]) {
                    inStr = 2;
                }
                if(inStr >= 0) {
                    for(j = 0; j < keyNum; j++) {
                        matchedLen[j] = 0;
                    }
                }
            }
        }
        return -1;
    }
  
    /**
     * 清除sql首尾多余的空白字符，删除其中的注释及无效的结束符
     * @param sql SQL脚本
     * @return 清除后的脚本
     */
    private static String clean(String sql) {
        String s = sql.trim();
        StringBuilder sb  = new StringBuilder(sql.length());
        boolean inStr = false;
        boolean inComment = false;
        char c;
        int len = s.length();
        
        for(int i = 0; i < len; i++) {
            c = s.charAt(i);
            if(c == '\0') {
                continue; //字符串中有异常的0结束符，在多语言编程中，如果不注意，则会出现
            }
            if(c == SQL_QUOTE) {
                if(!inComment) {
                    inStr = !inStr;
                }
            }
            
            if(!inStr) {
                if(c == '\n') {
                    inComment = false;
                } else if(c == '-' && i < len - 1 && s.charAt(i + 1) == '-') {
                    inComment = true;
                }
            }
            
            if(!inComment) {
                sb.append(c);
            }        
        }
        return sb.toString();
    }
    /**
     * 
     * @param sql 原始的sql，只可以是DML类SQL，可以用分号分隔多个
     * @param now 当前时间，可以是System.currentTimeMillis，
     *     也可以是@{#reqAt}，如果是@{#reqAt}，在运行时会被替换成接到请求的那个时间点
     * @return 修改后的sql
     */
    public static List<String> modifyDMLSqls(String sql, String now) {
        String s = clean(sql);
        SqlType sqType = getSqlType(s);
        if(sqType == SqlType.ERROR) {
            LOG.error("modifyDMLSqls,invalid sql `{}`, must start with a valid sql keyword", s);
            return null;
        }

        List<String> sqls = new ArrayList<>();
        //script只有在解释执行后才能知道sql，而select，则不可以有多个
        if(sqType.laterModify() || sqType == SqlType.SELECT) {
            sqls.add(s);
            return sqls;
        }

        int end;
        int start = 0;
        final int len = s.length();
        //每次都使用同一个sb，并且分配足够大的内存，避免频繁的创建对象、分配内存
        StringBuilder sb = new StringBuilder(Math.max(len * 2, 1024));
        while(start < len && (end = findSqlKeyWord(s, SQL_SEPARATOR, start)) > 0) {
            String oneSql = s.substring(start, end);
            sb.setLength(0); //清空sb，设为0时，不会导致重新分配内存
            if(modifyDMLSql(oneSql, now, sb) == SqlType.ERROR) {
                LOG.error("Invalid sql {} between {} and {}", oneSql, start, end);
                return null;
            }
            start = end + 1;
            sqls.add(sb.toString());
        }

        if(start < len) {
            sb.setLength(0);
            String oneSql = s.substring(start);
            if(modifyDMLSql(oneSql, now, sb) == SqlType.ERROR) {
                LOG.error("Invalid sql {} at {}", oneSql, start);
                return null;
            }
            sqls.add(sb.toString());
        }

        if(LOG.isDebugEnabled()) {
            LOG.debug("SQL:{}, Modified sqls:{}", s, sqls);
        }

        return sqls;
    }

    /**
     *
     * @param sql 原始的sql，只可以是DML类SQL
     * @param now 当前时间，可以是System.currentTimeMillis，
     *     也可以是@{NOW}，如果是@{NOW}，在运行时会被替换成System.currentTimeMillis
     * @return 修改后的sql
     */
    public static String modifyDMLSql(String sql, String now) {
        StringBuilder sb = new StringBuilder(sql.length() * 2);
        if(modifyDMLSql(sql, now, sb) == SqlType.ERROR) {
            LOG.error("Invalid sql {}", sql);
            return null;
        }

        String modifiedSql = sb.toString();
        if(LOG.isDebugEnabled()) {
            LOG.debug("SQL:{}, Modified sql:{}", sql, modifiedSql);
        }
        
        return modifiedSql;
    }

    /**
     * 替换数据库脚本
     * @param sql 数据库脚本
     * @param now 现在时间，可以为具体值，或占位符
     * @param sb 脚本缓存
     * @return sql类型，在sb中返回修改后的sql
     */
    private static SqlType modifyDMLSql(String sql, String now, StringBuilder sb) {
        String s = clean(sql);
        SqlType sqType = getSqlType(s);
        if(sqType == SqlType.ERROR) {
            LOG.error("modifyDMLSql,invalid sql `{}`, must start with a valid sql keyword", s);
            return sqType;
        }

        if(sqType.laterModify() || sqType == SqlType.SELECT) {
            sb.append(s);
            return sqType;
        }
        
        boolean result = true;
        if(sqType == SqlType.UPDATE) {
            result = modifyUpdate(s, now, sb);
        } else if(sqType == SqlType.REPLACE || sqType == SqlType.INSERT) {
            result = modifyInsert(s, now, sb);
        } else if(sqType == SqlType.DELETE) {
            result = modifyDelete(s, now, sb);
        }
        return result ? sqType : SqlType.ERROR;
    }

    /**
     * 如果是UPDATE，则在where之前增加一个更新update_time的操作，时间以UTC为准，
     * 不支持from语法，如where前出现from，最终会变成一个错误的update语句
     * @param sql 数据库脚本
     * @param now 可以是@{NOW}，也可以是时间字符串
     * @param sb 存放修改内容的内存
     * @return 如果sql格式异常，则返回false
     */
    private static boolean modifyUpdate(String sql, String now, StringBuilder sb) {
        int pos = findSqlKeyWord(sql, SQL_WHERE, 0, SQL_BRACKETS);
        if(pos == 0) {
            LOG.error("Update sql can't start with 'where', {}", sql);
            return false;
        }
        if(pos > 0) {
            sb.append(sql.substring(0, pos))
              .append(',').append(SEG_UPDATETIME)
              .append('=').append(now).append(' ')
              .append(sql.substring(pos));
        } else {
            //没有where的情况下，直接加在最后面
            sb.append(sql).append(',')
              .append(SEG_UPDATETIME).append('=').append(now);
        }
        return true;
    }

    /**
     * 同步时才需要在delete后面加一个update_time的限制，防止删除了后面新建的数据
     * @param sql 数据库脚本
     * @param now  sql执行的时间
     * @param sb 存放编辑内容的内存
     * @return 如果sql格式异常则返回false
     */
    private static boolean modifyDelete(String sql, String now, StringBuilder sb) {
        int pos = findSqlKeyWord(sql, SQL_WHERE);
        if(pos == 0) {
            LOG.error("Delete sql can't start with 'where', {}", sql);
            return false;
        }
        if(pos > 0) {
            int end = pos + SQL_WHERE.length + 1;
            sb.append(sql.substring(0, end)).append('(')
              .append(sql.substring(end))
              //防止在同步时，误删后面新增的数据，加上等于，使得可以删除当次请求产生的数据
              //在sqlite单个写入连接的情况下，这样处理没有问题
              //大型数据库支持并发写入，同时写入&删除同一条数据，而同步又不能删除的情况，可以忽略不计
              //并且大型数据库自身支持同步，可以不用webdb的同步能力
              .append(") and ").append(SEG_UPDATETIME).append("<=").append(now);
        } else {
            //没有where的情况下，直接加在最后面
            sb.append(sql).append(" where ").append(SEG_UPDATETIME).append("<=").append(now);
        }
        return true;
    }

    /**
     * 在replace/insert语法中增加update_time字段（UTC时间戳）
     * sqlite、mysql、postgresql等对upsert的支持不同，所以不支持upsert语法
     * @param sql 数据库脚本
     * @param now 当前时间，可以是具体值，也可以是占位符
     * @param sb 保存脚本的sb
     * @return 如果sql格式异常，则返回false
     */
    private static boolean modifyInsert(String sql, String now, StringBuilder sb) {
        if(findSqlKeyWord(sql, SQL_UPDATE_TIME) > 0) {
            sb.append(sql);
            return true; //自带update_time，不必做任何修改
        }
        
        int valsPos = findSqlKeyWord(sql, SQL_VALUES);
        if(valsPos <= 0) { //尝试replace/insert-select
            return modifyInsertSel(sql, now, sb);
        }

        int sqlLen = sql.length();
        int segsEndPos = valsPos - 1; //向回找')'，找到后，添加一个update_time
        while(sql.charAt(segsEndPos) != ')' && segsEndPos > 0){ 
            segsEndPos--;
        }

        if(segsEndPos <= 0) {
            LOG.error("Can't find ')' in sql before values,{}", sql);
            return false;
        }

        int deep = 0;
        char ch;
        boolean inQuot = false;
        int valCount = 0;
        
        sb.append(sql.substring(0, segsEndPos))
         .append(',').append(SEG_UPDATETIME).append(") values");

        /*
         * 支持批量insert/replace，也即是values后面跟了多行记录；
         * 在处理时，忽略了''中的所有内容
         */
        for(int i = valsPos + SQL_VALUES.length; i < sqlLen; i++){
            ch = sql.charAt(i);
            if(ch=='\'') {
                inQuot = !inQuot;
            }
            
            if(!inQuot) { //在字符串中的所有字符，直接copy
                if(ch == '(') {
                      deep++;
                } else if(ch == ')') {
                    deep--;
                    if(deep == 0) { //添加now
                        sb.append(',').append(now);
                        valCount++;
                    }
                }
            }
            sb.append(ch);
        }
        if(valCount == 0) {
            LOG.error("`{}` is invalid,or brackets not match", sql);
            return false;
        }
        
        return true;
    }
    
    private static boolean modifyInsertSel(String sql, String now, StringBuilder sb) {
        int selPos = findSqlKeyWord(sql, SQL_SELECT);
        if(selPos <= 0) {
            LOG.error("Invalid replace/insert-select sql, no select, {}", sql);
            return false;
        }

        int segsEndPos = selPos - 1; //向回找')'，找到后，添加一个update_time
        while(sql.charAt(segsEndPos) != ')' && segsEndPos > 0){ 
            segsEndPos--;
        }

        if(segsEndPos <= 0) {
            LOG.error("Can't find ')' in sql before select,{}", sql);
            return false;
        }

        
        int fromPos = findSqlKeyWord(sql, SQL_FROM, selPos);
        
        sb.append(sql.substring(0, segsEndPos)).append(',')
          .append(SEG_UPDATETIME);
        if(fromPos <= 0) {
            sb.append(sql.substring(segsEndPos))
              .append(',').append(now);
        } else {
            sb.append(sql.substring(segsEndPos, fromPos))
              .append(',').append(now).append(' ')
              .append(sql.substring(fromPos));
        }
        
        return true;
    }
    
    public static String addInsertIgnore(String sql) {
        int pos = findSqlKeyWord(sql, SQL_INSERTIGNORE);
        if(pos > 0) {
            return sql;
        }
        pos = findSqlKeyWord(sql, SQL_INTO);
        return sql.substring(0, pos) + SQL_SINSERTIGNORE + sql.substring(pos);
    }

    private static final Pattern SQL_CREATE_TABLE = Pattern.compile("(?is)^create\\s+table.+$");
    /**
     * 在create table中增加update_time字段，alter、drop语句不用编辑。
     * update_time字段未加索引，所以项目中使用时需要注意。
     * 在数据表升级时，如果用到update_time字段，需要另行增加索引
     * @param sql 数据库脚本
     * @return 修改后的sql
     */
    public static String modifyDDLSql(String sql) {
        if(!SQL_CREATE_TABLE.matcher(sql).matches()) {
            return sql; //不是create table，比如建索引，则直接返回
        }

        /*
         *  在建表语句的第一行增加update_time字段，定长字段尽量放前面。
         *  业务sql应该做好索引优化，所以不在update_time字段上自动建索引。
         *  update_time主要用途是在同步时，避免delete语句删除了错误的记录，
         *  或者update语句执行了过时的更新
         */
        String s = removeComment(sql);
        int pos = s.indexOf('(') + 1;
        return s.substring(0, pos) + '\n'
                + SEG_UPDATETIME + " bigint(8) NOT NULL,"
                + s.substring(pos);
    }

    /**
     * 从create table的sql中找到表名称，找到第一个'('，然后往回找到表名前面的一个空格
     * @param sql 数据库脚本
     * @return 数据库名称
     */
    public static String getTableName(String sql) {
        int end = sql.indexOf('(');
        int start;
        boolean nameStarted = false;
        for(start = end - 1; start > 0; start--) {
            if(Character.isWhitespace(sql.charAt(start))) {
                if(nameStarted) {
                    start++; //trim left
                    break;
                }
                end = start; //trim right
            } else {
                nameStarted = true;
            }
        }
        return sql.substring(start, end);
    }
    
    public static String removeComment(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int len = sql.length();
        boolean inStr = false;
        boolean inComment = false;
        char ch;

        for(int i = 0; i < len; i++) {
            ch = sql.charAt(i);
            if(inComment) { //注释中的，直接丢弃
                if(ch == '\n') {
                    inComment = false;
                    sb.append(ch);
                }
                continue;
            }

            if(ch == '\'') {
                inStr = !inStr;
            }

            if(inStr) { //在字符串中的直接copy，一直到字符串结尾，字符串中不判断注释
                sb.append(ch);
                continue;
            }

            if(ch == '-') {
                if(i < len - 1 && sql.charAt(i + 1) == '-') {
                    inComment = true;
                    continue;
                }
            }

            sb.append(ch);
        }
        return sb.toString();
    }
    //-------------------------------------------------------------------------
    /**
     * 执行多条sql
     * @param conn 数据库连接
     * @param sqls 多条SQL
     * @return 所有sql执行后影响到的行数，小于0时表示执行失败
     * @throws SQLException 数据库异常
     */
    public int executeRawDML(AbsConnection conn, List<String> sqls) throws SQLException {
        int num;
        int total = 0;
        for(String sql : sqls) {
            num = executeRawDML(conn, sql);
            if(num < 0) {
                return num;
            }
            total += num;
        }

        return total;
    }
    
    public int executeRawDML(AbsConnection conn, String[] sqls) throws SQLException {
        int num;
        int total = 0;

        for (String sql : sqls) {
            num = executeRawDML(conn, sql);
            if (num < 0) {
                return num;
            }
            total += num;
        }

        return total;
    }
    //-------------------------------------------------------------------------
    /**
     * rdb服务端处理api请求
     * @param req 请求参数
     * @param respData 写入响应信息
     * @return 处理结果
     */
    public HandleResult handleRequest(AbsServerRequest req, Map<String, Object> respData) {
        if(ValParser.getAsBool(req.params(), DB_REQ_ISWRITE)) {
            if(!writable()) {
                return new HandleResult(RetCode.NO_RIGHT, "can't write,state=" + this.state.name());
            }
            return writeHandle(req, respData);
        }
        return readHandle(req, respData);
    }

    /**
     * 根据服务名、数据库名获得实例，必须保证在之前创建过
     * @param service 服务名
     * @param db 数据库名
     * @return 数据库worker实列
     */
    public static AbsRDBWorker instance(int cid, String service, String db) {
        String id = dbId(cid, service, db);
        return DBWorkers.get(id);
    }
    /**
     * 
     * @param dbNo webdb编号，一个物理实例中可以有多个webdb
     * @param builder 创建数据库实例的工厂
     * @return 数据库worker实列
     */
    public static AbsRDBWorker instance(int dbNo, AbsRDBWBuilder builder) {
        if(builder == null) {
            return null;
        }
        String id = dbId(builder.cid, builder.service, builder.db);
        AbsRDBWorker dw = DBWorkers.get(id);
        if(dw != null) {
            return dw; //绝大部分情况，在此返回
        }

        /*
         * 尽管DBWorkers可保障原子性，但是不能保证build过程原子性，
         * 当两个请求同时进入时，两个请求get都为空，都会build，所以此处要同步。
         * 创建数据库实例不是一个频繁的操作，所以没有用
         * ConcurrentHashMap+Future/FutureTask方法实现互斥
         * 而是直接用lock，如果使用synchronized(DBWorkers)，spotbugs会告警
         * Synchronization performed on java.util.concurrent.ConcurrentHashMap
         * 在此处并无多大影响，但是为了消除告警，使用lock
         */
        InstanceLock.lock();
        try {
            if((dw = DBWorkers.get(id)) == null) {//多线程并发的情况，再次判断
                if((dw = builder.build(dbNo)) == null) {
                    LOG.error("Fail to create rdb {}", id);
                    return null;
                }
                DBWorkers.put(id, dw);
            }
        } catch (Exception e) {
            LOG.error("Fail to create instance of {}", id, e);
        } finally {
            InstanceLock.unlock();
        }
        return dw;
    }

    public static boolean removeInstance(int cid, String service, String dbName) {
        String id = dbId(cid, service, dbName);
        if(!DBWorkers.containsKey(id)) {
            return true;
        }

        /*
         * DBWorkers绝大部分情况都不需要同步，
         * 但是，创建与关闭数据库不能并发或者进行多次，所以需要在此同步
         */
        AbsRDBWorker db;
        LOG.debug("remove RDB instance {}", id);
        InstanceLock.lock();//注意：需要互斥的是db.close，而不是DBWorkers.remove
        try {
            if((db = DBWorkers.get(id)) != null) { //再次判断
                db.close();
                if(db.remove()) {
                    DBWorkers.remove(id);
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.error("Fail to remove instance {}", id, e);
        } finally {
            InstanceLock.unlock();
        }
        
        return false;
    }
    
    protected static CompletableFuture<HandleResult> getAdminToken(ServiceInfo si, int cid, String[] services) {
        String token = BiosClient.appToken(si, IConst.SERVICE_COMPANY, "*", 3600);
        if(StringUtil.isEmpty(token)) {
            LOG.warn("Can't create app token for webdb({})", ChannelConfig.instance().localHttpAddr());
            return HandleResult.future(RetCode.NO_RIGHT, "can't create app token");
        }
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
            .traceId(IConst.SERVICE_COMPANY + '_' + cid)
            .cid(cid)
            .url("webdb/adminToken") //私有云中调用兼容接口/frm/service/vCloud/AdminToken
            .token(token)
            .put("services", services)
            .nodeId(cid);
        return BiosClient.post(req);
    }

    /**
     * 销毁实例
     */
    public static void destroy() {
        for(Map.Entry<String, AbsRDBWorker> one : DBWorkers.entrySet()) {
            AbsRDBWorker db = one.getValue();
            if(db == null) {
                continue;
            }
            LOG.info("Close rdb {}", one.getKey());
            try {
                db.close();
            } catch (Exception e) {
                LOG.error("Fail to close rdb instance of {}", one.getKey(), e);
            }
        }
        DBWorkers.clear();
    }

    /**
     * wedb服务端编译请求
     * 如果sql中出现@[!xxx]参数需要替换，或使用了js:,rs:开头，则会导致db服务端编译替换。
     * 应尽量避免此种情况出现，因为编译替换的操作耗费db服务端的CPU。
     * @param req 请求
     * @param respData 响应
     * @param dbReq 数据库请求参数
     * @return 编译结果，如果无错误，则返回null
     */
    private static HandleResult compileRequest(AbsServerRequest req, Map<String, Object> respData, Map<String, Object> dbReq) {
        String when = ValParser.getAsStr(dbReq, SQL_WHEN);
        if(!StringUtil.isEmpty(when)) {
            String formula = compileScript(when, req, respData);
            if(StringUtil.isEmpty(formula)) {
                return new HandleResult(RetCode.INTERNAL_ERROR, "invalid 'when' config");
            }
            //在RDBProcessor中已替换了&&、||，此处直接计算即可
            if(Calculator.calculate(formula) <= 0) {
                return new HandleResult(RetCode.NO_OPERATION, "needn't run");
            }
        }
        
        String sql = ValParser.getAsStr(dbReq, SQL_SQL);
        boolean changed = false;
        /*
         * 请求侧即已确定sql中是否要用到上一步返回结果，
         * 也就是是否存在`@[!...]`
         */
        if(ValParser.getAsBool(dbReq, SQL_NEEDCOMPILE, true)) {
            String compiledSql = compileScript(sql, req, respData);
            if (StringUtil.isEmpty(compiledSql)) {
                LOG.error("Fail to compile sql `{}`", sql);
                //String name = ValParser.getAsStr(request, SQL_NAME);
                //respData.put(name + HANDLE_RESULT, sn);
                //如果sql编译失败，说明配置有问题，必须立刻结束
                return new HandleResult(RetCode.INTERNAL_ERROR, "wrong config");
            }
            sql = compiledSql;
            changed = true;
        }
        
        SqlType type = getSqlType(sql);
        if(type.laterModify()) {
            /*
             * 在sql中引入js的能力，请求侧不会编译运行，发送到webdb中执行后返回一段sql。
             * 得到sql后，再在webdb中编辑sql，添加update_time字段。
             * 嵌在script中的sql，要比纯粹的编辑sql的效率要低50倍。
             * 【注意】有且只能有一个sql字符串返回
             */
            if(type == SqlType.SCRIPT) {
                sql = JsEngine.getString(sql.substring(JS_HEAD.length()));
            } else {
                sql = sql.substring(RS_HEAD.length());
            }
            //可能是sql错误，也可能是直接的返回结果
            HandleResult hr = HandleResult.tryParse(sql);
            if(hr != null) {
                return hr;
            }
            //通常一个sql只有一个dml语句，但是js中不易控制，所以强制修改一次
            //并且，js中生成的sql也没有添加update_time
            dbReq.put(SQL_NEEDMODIFY, true);
            changed = true;
        }

        if(changed) {
            dbReq.put(SQL_SQL, sql);
        }
        return null;
    }
    
    /**
     * 逐条执行sql，其中可以夹杂查询sql，作为后继sql的输入，使用@[!xxx]作为占位符
     * @param req 请求
     * @param globalData 记录前面步骤执行的结果，用于在后面执行的sql中替换掉@[var_name]变量
     * @return api返回码
     */
    private HandleResult writeHandle(AbsServerRequest req, Map<String, Object> globalData) {
        Map<String, Object> params = req.params();
        List<Object> sqls = ValParser.getAsList(params, RDB_REQ_SQLS);
        if(sqls == null || sqls.isEmpty()) {
            return new HandleResult(RetCode.WRONG_PARAMETER);
        }
        String name;
        String resultName;
        String sql;
        Map<String, Object> dbReq;
        List<Integer> ignores;
        boolean needModify;
        boolean execOK = true;
        HandleResult hr;
        List<String> rawSqls = new ArrayList<>();
        AbsConnection conn = null; //因为endTransaction中用到，所以不用try(conn=...)
        String now = ValParser.getAsStr(params, DB_REQ_TIME, Long.toString(System.currentTimeMillis()));
        int num;
        String info;
        int total;
        int retCode;
        int affected;
        Map<String, Object> expected;
        Map<String, Object> data = new HashMap<>(globalData);
        boolean isMulti;

        try {
            conn = getWriteConn();
            beginTransaction(conn);
            for(Object o : sqls) {
                dbReq = ValParser.parseObject(o);
                if((hr = compileRequest(req, data, dbReq)) != null) {
                    if(hr.code != RetCode.OK) {
                        if(hr.code == RetCode.NO_OPERATION) {
                            continue; //无需运行，直接执行下一条
                        }
                        execOK = false; //finally中用到
                        return hr; //编译sql脚本发生错误
                    }
                    data.putAll(hr.data);//配置的rs、js，执行结果是json，直接返回数据
                    if(ValParser.getAsBool(dbReq, SQL_TO_RESP, true)) {
                        globalData.putAll(hr.data);
                    }
                    continue;
                }
                
                sql = StringUtil.trim(ValParser.getAsStr(dbReq, SQL_SQL));
                if(StringUtil.isEmpty(sql)) { //只有在script的情况，才可能返回空sql
                    return HandleResult.OK;
                }

                if(LOG.isDebugEnabled()) {
                    int cid = req.cid();
                    LOG.debug("Execute writes in db {}.{} of {},sql:`{}`", service, dbName, cid, sql);
                }
                
                name = ValParser.getAsStr(dbReq, SQL_NAME);
                ignores = ValParser.getAsIntList(dbReq, SQL_IGNORES, RetCode.OK);
                //多个sql的情况，只要有一条写，则整个为写操作，所以可能存在读操作
                if(getSqlType(sql) == SqlType.SELECT) {
                    isMulti = ValParser.getAsBool(dbReq, SQL_MULTI, true);
                    hr = query(conn, dbReq, name, sql, isMulti);
                    if(hr.code == RetCode.OK) {
                        data.putAll(hr.data);
                        if(ValParser.getAsBool(dbReq, SQL_TO_RESP, true)) {
                            globalData.putAll(hr.data);
                        }
                        continue;
                    }
                    if(canIgnore(ignores, hr.code)) {
                        continue;
                    }
                    execOK = false;
                    return hr;
                }

                needModify = ValParser.getAsBool(dbReq, SQL_NEEDMODIFY, true);
                try {
                    if(needModify) {
                        //一次只能执行一条DML语句，所以先按分号将sql转成list，逐条执行
                        List<String> sqlList = modifyDMLSqls(sql, now);
                        if(sqlList == null || sqlList.isEmpty()) {
                            return new HandleResult(RetCode.WRONG_PARAMETER);
                        }
                        rawSqls.addAll(sqlList);
                        affected = executeRawDML(conn, sqlList);
                    } else { //如果服务侧不编译，则必须一行一个sql
                        rawSqls.add(sql);
                        affected = executeRawDML(conn, sql);
                    }

                    //增删改影响的行数，如果没有行受影响，表示要修改的记录不存在
                    expected = ValParser.getAsObject(dbReq, SQL_EXPECTED);
                    if(expected != null) {
                        num = ValParser.getAsInt(expected, SQL_EXPECTED_NUM, -1); //不设置，或设为-1是等效的
                        //判断受影响的行数是否正确，如果为-1，则只要有记录被修改就可以
                        if(num != affected && !(affected > 0 && num == -1)) {
                            retCode = ValParser.getAsInt(expected, CFG_ERROR_CODE, RetCode.NOT_EXISTS);
                            info = ValParser.getAsStr(expected, CFG_ERROR_INFO);
                            if(StringUtil.isEmpty(info)) {
                                info = RetCode.getInfo(retCode);
                            }
                            return new HandleResult(retCode, info);
                        }
                    }
                    resultName = name + HANDLE_RESULT;
                    total = ValParser.getAsInt(data, resultName, 0) + affected;
                    data.put(resultName, total); //受影响的行数，sql名称相同的被汇总，因为在请求时，多行sql会被分解成多个
                    if(ValParser.getAsBool(dbReq, SQL_TO_RESP, false)) {
                        globalData.put(resultName, total);
                    }
                } catch (SQLException e) {
                    LOG.error("Fail to execute {}.`{}` in `{}`", name, sql, this, e);
                    //respData.put(name + HANDLE_RESULT, 0);
                    if(!canIgnore(ignores, RetCode.DB_ERROR)) {
                        execOK = false;
                        return new HandleResult(RetCode.DB_ERROR);
                    }
                }
            }
        } catch(Exception e) {
            execOK = false;
            LOG.error("Fail to handle request from {} in `{}`", req.remoteAddr(), this, e);
            return new HandleResult(RetCode.DB_ERROR, "sql error");
        } finally {
            if(conn != null) {
                endTransaction(conn, execOK);
                try {
                    conn.close();
                } catch (Exception e) {
                    LOG.error("Fail to close connection", e);
                }
                int size = rawSqls.size();
                if(execOK && size > 0) {
                    sync(rawSqls);
                }
            }
        }
        return new HandleResult(data);
    }

    private boolean canIgnore(List<Integer> ignores, int code) {
        if(ignores == null) {
            return false;
        }
        for(int c : ignores) {
            if(c < 0) {
                return true;
            }
            if(c == code) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 逐条执行查询sql，多个sql的执行结果集全部存入respData中
     * @param req 请求
     * @param globalData 记录前面步骤执行的结果，用于在后面执行的sql中替换掉@[var_name]变量
     * @return 读请求处理结果
     */
    private HandleResult readHandle(AbsServerRequest req, Map<String, Object> globalData) {
        List<Object> sqls = ValParser.getAsList(req.params(), RDB_REQ_SQLS);
        if(sqls == null || sqls.isEmpty()) {
            return new HandleResult(RetCode.WRONG_PARAMETER, "no " + RDB_REQ_SQLS);
        }

        List<Integer> ignores;
        String sql;
        boolean any = ValParser.getAsBool(req.params(), DB_REQ_ANY);
        HandleResult hr;
        Map<String, Object> dbReq;
        int size = sqls.size();
        boolean isMulti;
        String name;
        Map<String, Object> data = new HashMap<>(globalData);
        
        try (AbsConnection conn = getReadConn()) {
            for(int i = 0; i < size; i++) {
                dbReq = ValParser.parseObject(sqls.get(i));
                if((hr = compileRequest(req, data, dbReq)) != null) {
                    if(hr.code != RetCode.OK) {
                        if(hr.code == RetCode.NO_OPERATION) {
                            continue; //无需运行，直接执行下一条
                        }
                        return hr; //编译sql脚本发生错误
                    }
                    data.putAll(hr.data); //配置的rs、js，执行结果是json，直接返回数据
                    if(ValParser.getAsBool(dbReq, SQL_TO_RESP, true)) {
                        globalData.putAll(hr.data);
                    }
                    continue;
                }

                sql = ValParser.getAsStr(dbReq, SQL_SQL);
                if(StringUtil.isEmpty(sql)) { //只有在script的情况，才可能返回空sql，比如初始化公共js函数
                    return HandleResult.OK;
                }

                isMulti = ValParser.getAsBool(dbReq, SQL_MULTI, true);
                name = ValParser.getAsStr(dbReq, SQL_NAME);

                hr = query(conn, dbReq, name, sql, isMulti);
                if(LOG.isDebugEnabled()) {
                    LOG.debug("Execute `{}` in `{}`,result:{}", sql, this, hr.brief());
                }
                if(hr.code == RetCode.OK) {
                    data.putAll(hr.data);
                    if(ValParser.getAsBool(dbReq, SQL_TO_RESP, true)) {
                        globalData.putAll(hr.data);
                    }
                    if(any) { //有一个成功则返回
                        break;
                    }
                    continue;
                }
                /*
                 * 多个读操作的情况，如果设置了any，顺序往后执行时，
                 * 碰到第一个成功的，则返回。
                 * 如果一直失败，只要不是最后一个，则必须忽略错误，
                 * 否则如果未设置可以忽略错误，则碰到错误就返回
                 */
                if(any) {
                    if(i < size - 1) { //任何一个成功的情况，只要不是最后一个错误都可以忽略
                        ignores = IgnoresAll;
                    } else {
                        ignores = ValParser.getAsIntList(dbReq, SQL_IGNORES, RetCode.OK);
                    }
                } else {
                    ignores = ValParser.getAsIntList(dbReq, SQL_IGNORES, RetCode.OK);
                }
                if(canIgnore(ignores, hr.code)) {
                    LOG.debug("Execute `{}` in `{}`, retCode:{}, but ignored {}",
                            sql, this, RetCode.name(hr.code), ignores);
                    continue; //如果错误可忽略，则继续
                }

                return hr;
            }
        } catch(MeshException e) {
            LOG.error("Fail to get connection in {}.{}", service, dbName, e);
            return new HandleResult(RetCode.DB_ERROR, "sql error");
        }

        return new HandleResult(data);
    }

    /**
     * 执行单条查询sql
     * @param conn 虚拟连接
     * @param dbReq db请求
     * @param name 请求的名称
     * @param sql 请求的脚本
     * @param isMulti 结果集是否为多行
     * @return 执行结果
     */
    private HandleResult query(AbsConnection conn, Map<String, Object> dbReq, String name, String sql, boolean isMulti) {
        Map<String, Object> data = new HashMap<>();
        try {
            if (isMulti) { //多行结果集
                String metas = ValParser.getAsStr(dbReq, SQL_METAS, META_EACH);
                if(metas.equalsIgnoreCase(META_EACH)) {
                    List<Map<String, Object>> rows = queryMaps(sql);
                    if (rows != null && !rows.isEmpty()) {
                        data.put(name, rows);
                        return new HandleResult(data);
                    }
                } else if(metas.equalsIgnoreCase(META_NONE)){
                    List<Object[]> rows = queryArrays(conn, sql);
                    if (rows != null && !rows.isEmpty()) {
                        data.put(name, rows);
                        return new HandleResult(data);
                    }
                } else if(metas.equalsIgnoreCase(META_ONECOL)) { //只有一列，直接返回一个数组
                    List<Object> rows = querySingleList(conn, sql);
                    if (rows != null && !rows.isEmpty()) {
                        data.put(name, rows);
                        return new HandleResult(data);
                    }
                } else if(metas.equalsIgnoreCase(META_KV)) { //第一列为k，其他列为v，如多于2列，v为一个数组
                    List<Object[]> rows = queryArrays(conn, sql);
                    if (rows != null && !rows.isEmpty()) {
                        if(rows.get(0).length < 2) {
                            return new HandleResult(RetCode.DATA_WRONG);
                        }
                        Map<String, Object> kv = new HashMap<>();
                        String k;
                        Object v;
                        for(Object[] row : rows) {
                            k = String.valueOf(row[0]);
                            v = row.length == 2 ? row[1] : Arrays.copyOfRange(row, 1, row.length);
                            kv.put(k, v);
                        }
                        data.put(name, kv);
                        return new HandleResult(data);
                    }
                } else if(metas.equalsIgnoreCase(META_KO)) { //第一列为k，其他列为v，如多于2列，v为一个map
                    List<Object[]> rows = queryArraysWithMetaTail(conn, sql);
                    if (rows != null && rows.size() > 1) {
                        int colNum = rows.get(0).length;
                        if(colNum < 2) {
                            return new HandleResult(RetCode.DATA_WRONG);
                        }
                        int last = rows.size() - 1;
                        String[] colMetas = ValParser.toStrArray(rows.get(last));
                        rows.remove(last);
                        Map<String, Object> kv = new HashMap<>();
                        String k;
                        Map<String, Object> v;
                        for(Object[] row : rows) {
                            k = String.valueOf(row[0]);
                            v = new HashMap<>();
                            for(int i = 1; i < colNum; i++) {
                                v.put(colMetas[i], row[i]);
                            }
                            kv.put(k, v);
                        }
                        data.put(name, kv);
                        return new HandleResult(data);
                    }
                } else {
                    List<Object[]> rows = queryArraysWithMetaTail(conn, sql);
                    if (rows != null && !rows.isEmpty()) {
                        int last = rows.size() - 1;
                        Object[] colMetas = rows.get(last);
                        rows.remove(last);
                        data.put(name, rows);
                        data.put(metas, colMetas); //metas是meta信息在resp中的名称
                        return new HandleResult(data);
                    }
                }
                //多行的情况，虽然没有结果，但是返回空记录，防止调用方处理时出现错误
                data.put(name, new ArrayList<>());
            } else { //单行结果集
                Map<String, Object> row = queryMap(conn, sql);
                boolean isMerge = ValParser.getAsBool(dbReq, SQL_MERGE, true);
                if (row != null && !row.isEmpty()) {
                    if (isMerge) {
                        data.putAll(row); //只有单行的结果集才可以合并
                    } else {
                        data.put(name, row);
                    }
                    return new HandleResult(data);
                }
                if(!isMerge) {
                    //虽然没有结果，但是返回空记录，防止客户端处理时出现错误
                    data.put(name, new HashMap<String, Object>());
                }
            }
            return HandleResult.NotExists;
        } catch (Exception e) {
            LOG.error("Fail to execute sql {}.`{}` in db `{}`", name, sql, this, e);
            return new HandleResult(RetCode.DB_ERROR);
        }
    }
}
