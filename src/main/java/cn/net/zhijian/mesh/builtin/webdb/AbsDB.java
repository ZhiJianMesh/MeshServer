package cn.net.zhijian.mesh.builtin.webdb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.dbworker.SqliteWorker.SqliteBuilder;
import cn.net.zhijian.mesh.dbworker.JDBCWorker.JDBCBuilder;
import cn.net.zhijian.mesh.dbworker.MysqlWorker.MysqlBuilder;
import cn.net.zhijian.mesh.dbworker.PostgreWorker.PostgreBuilder;
import cn.net.zhijian.mesh.dbworker.OracleWorker.OracleBuilder;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker.AbsRDBWBuilder;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

abstract class AbsDB implements IDBConst {
    private static final Logger LOG = LogUtil.getInstance();

    static final String SEG_STATUS = "status"; //状态

    public final int level;
    /*
     * webdb只关心本实例支撑哪个no与sharding范围，并且定期上报给bios，
     * 它并不管发过来的数据是否真实从属于对应的sharding，
     * 这个保证需要在dbclient与各个dbprocessor中实现
     */
    public final int no; //数据库实例分区号，不同公司划分到不同分区
    public final int shardStart; //包括，androidserver中每个实例都只支持全部分段
    public final int shardEnd; //不包括
    
    public final int writeConnNum; //写连接个数
    public final int readConnNum; //读连接个数
    public final NodeAddress[] slaves; //从实例
    public final String mode;
    
    abstract AbsRDBWBuilder getBuilder(ServiceInfo si, int cid, String service, String db);
    abstract DBType type();
    abstract Map<String, Object> toMap();
    //本身具备同步能力的数据库，固定返回false
    //对于sqlite等文件数据库，有slaves的情况，才需要启动定时备份，并且在主库执行定时备份
    abstract boolean needBackup();
    
    abstract boolean parseExt(Map<String, Object> cfg);

    AbsDB(int dbNo, int level, int shardingStart, int shardingEnd,
          int writeConnNum, int readConnNum, NodeAddress[] slaves, String mode) {
        this.level = level;
        this.shardStart = shardingStart;
        this.shardEnd = shardingEnd;
        this.no = dbNo;
        this.writeConnNum = writeConnNum;
        this.readConnNum = readConnNum;
        this.mode = mode;
        this.slaves = slaves;
    }

    public static AbsDB parse(Map<String, Object> cfg) {
        if(cfg == null || cfg.isEmpty()) {
            return null;
        }
        
        int level = ValParser.getAsInt(cfg, CFG_SEG_LEVEL);
        int shardingStart = ValParser.getAsInt(cfg, CFG_SEG_SHARDSTART, 0);
        int shardingEnd = ValParser.getAsInt(cfg, CFG_SEG_SHARDEND, MAX_SHARDING_NUM);

        if(shardingStart < 0 || shardingStart >= shardingEnd || shardingEnd > MAX_SHARDING_NUM) {
            LOG.error("Invalid sharding config {}->{}", shardingStart, shardingEnd);
            return null;
        }

        int dbNo = ValParser.getAsInt(cfg, CFG_SEG_NO, 0);
        
        int writeConnNum = ValParser.getAsInt(cfg, CFG_SEG_WRITECONN, 1);
        int readConnNum = ValParser.getAsInt(cfg, CFG_SEG_READCONN, DEFAULT_MAX_CONN_NUM);
        if(writeConnNum <= 0) {
            LOG.error("Invalid {}:{}", CFG_SEG_WRITECONN, writeConnNum);
            return null;
        }
        if(readConnNum <= 0) {
            LOG.error("Invalid {}:{}", CFG_SEG_READCONN, readConnNum);
            return null;
        }
        
        NodeAddress[] slaves = null;
        //备份实例的地址，可以有多个，以逗号分隔
        String ss = ValParser.getAsStr(cfg, CFG_SEG_SLAVES);
        if(!StringUtil.isEmpty(ss)) {
            List<NodeAddress> list = new ArrayList<>();
            String local = ChannelConfig.instance().localHttpAddr();
            for(String s : ss.split(",")) {
                s = s.trim();
                if(!local.equals(s)) { //删除本地ip
                    list.add(new NodeAddress(IConst.SERVICE_WEBDB, s));
                }
            }
            slaves = list.toArray(new NodeAddress[] {});
        }
        
        String mode = ValParser.getAsStr(cfg, CFG_SEG_MODE, DB_MASTER).toLowerCase();
        String sType = ValParser.getAsStr(cfg, CFG_SEG_TYPE, DBType.SQLITE.name()).toUpperCase();
        AbsDB db = null;
        if(sType.equals(DBType.SQLITE.name())) {
            db = new SqliteDB(dbNo, level, shardingStart, shardingEnd, readConnNum, slaves, mode);
            return db.parseExt(cfg) ? db : null;
        }

        String dbUrl = ValParser.getAsStr(cfg, CFG_SEG_DBURL, null);
        String account = ValParser.getAsStr(cfg, "user");
        String pwd = ValParser.getAsStr(cfg, "pwd");
        if(sType.equals(DBType.MYSQL.name())) {
            db = new MysqlDB(dbNo, level, shardingStart, shardingEnd, writeConnNum, readConnNum,
                    dbUrl, slaves, mode, account, pwd);
        } else if(sType.equals(DBType.POSTGRE.name())) {
            db = new PostgreDB(dbNo, level, shardingStart, shardingEnd, writeConnNum, readConnNum,
                    dbUrl, slaves, mode, account, pwd);
        } else if(sType.equals(DBType.ORACLE.name())) {
            db = new OracleDB(dbNo, level, shardingStart, shardingEnd, writeConnNum, readConnNum,
                    dbUrl, slaves, mode, account, pwd);
        } else { //使用默认的jdbc工作类，可能因alter table add column无法执行，导致无法启动
            db = new JdbcDB(dbNo, level, shardingStart, shardingEnd, writeConnNum, readConnNum,
                    dbUrl, slaves, mode, account, pwd);
        }
        return db.parseExt(cfg) ? db : null;
    }

    public static AbsDB defaultDb() {
        LOG.debug("Create default sqlite webdb,dbNo:{},sharding:(0,{}),maxConn:{},no slaves",
                   LOCAL_DBNO, MAX_SHARDING_NUM, DEFAULT_MAX_CONN_NUM);
        return new SqliteDB(LOCAL_DBNO, 0, 0, MAX_SHARDING_NUM, DEFAULT_MAX_CONN_NUM, null, DB_MASTER);
    }
    
    static class SqliteDB extends AbsDB {
        private SqliteDB(int dbNo, int level, int shardingStart, int shardingEnd, int readConnNum, NodeAddress[] slaves, String mode) {
            super(dbNo, level, shardingStart, shardingEnd, 1, readConnNum, slaves, mode);
        }

        @Override
        AbsRDBWBuilder getBuilder(ServiceInfo si, int cid, String service, String db) {
            SqliteBuilder builder = new SqliteBuilder(si, cid, service, db,
                    1/*sqlite只能一个写连接*/, readConnNum, AbsPlatform.dbRoot());
            //builder.backup(true);
            builder.slaves(this.slaves);
            builder.sharding(shardStart, shardEnd);

            return builder;
        }

        @Override
        boolean parseExt(Map<String, Object> cfg) {
            return true;
        }

        @Override
        DBType type() {
            return DBType.SQLITE;
        }
        
        @Override
        Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put(CFG_SEG_TYPE, type().name());
            map.put(CFG_SEG_NO, no);
            map.put(CFG_SEG_LEVEL, level);
            map.put(CFG_SEG_SHARDSTART, shardStart);
            map.put(CFG_SEG_SHARDEND, shardEnd);
            map.put(CFG_SEG_WRITECONN, writeConnNum);
            map.put(CFG_SEG_READCONN, readConnNum);
            map.put(CFG_SEG_SLAVES, NodeAddress.join(slaves, ","));
            //主从实例的状态标识不同
            map.put(SEG_STATUS, DB_MASTER.equals(this.mode) ? "M" : "S");
            return map;
        }

        @Override
        public boolean needBackup() {
            return this.slaves != null && this.slaves.length > 0;
        }
    }

    static class JdbcDB extends AbsDB {
        protected final String dbUrl;
        protected final String account;
        protected final transient String pwd;
        
        protected JdbcDB(int dbNo, int level, int shardingStart, int shardingEnd,
                       int writeConnNum, int readConnNum, String dbUrl, NodeAddress[] slaves, String mode,
                       String account, String pwd) {
            super(dbNo, level, shardingStart, shardingEnd, writeConnNum, readConnNum, slaves, mode);
            this.dbUrl = dbUrl;
            this.account = account;
            this.pwd = pwd;
        }

        @Override
        AbsRDBWBuilder getBuilder(ServiceInfo si, int cid, String service, String db) {
            JDBCBuilder builder = new JDBCBuilder(cid, service, db,
                    account, pwd,
                    writeConnNum, readConnNum, dbUrl);
            builder.slaves(this.slaves);
            builder.sharding(shardStart, shardEnd);

            return builder;
        }

        @Override
        public boolean needBackup() {
            return false;
        }

        @Override
        boolean parseExt(Map<String, Object> cfg) {
            return true;
        }

        @Override
        DBType type() {
            return DBType.JDBC;
        }
        
        @Override
        Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put(CFG_SEG_TYPE, type().name());
            map.put(CFG_SEG_NO, no);
            map.put(CFG_SEG_LEVEL, level);
            map.put(CFG_SEG_SHARDSTART, shardStart);
            map.put(CFG_SEG_SHARDEND, shardEnd);
            map.put(CFG_SEG_WRITECONN, writeConnNum);
            map.put(CFG_SEG_READCONN, readConnNum);
            map.put(CFG_SEG_DBURL, this.dbUrl);
            map.put(CFG_SEG_SLAVES, NodeAddress.join(slaves, ","));
            map.put(SEG_STATUS, DB_MASTER.equals(this.mode) ? "M" : "S");
            return map;
        }
    }
    
    static class MysqlDB extends JdbcDB {
        protected MysqlDB(int dbNo, int level, int shardingStart, int shardingEnd,
                int writeConnNum, int readConnNum, String dbUrl, NodeAddress[] slaves, String mode,
                String account, String pwd) {
             super(dbNo, level, shardingStart, shardingEnd, writeConnNum, readConnNum, dbUrl, slaves, mode, account, pwd);
        }
        
        @Override
        AbsRDBWBuilder getBuilder(ServiceInfo si, int cid, String service, String db) {
            MysqlBuilder builder = new MysqlBuilder(cid, service, db,
                    account, pwd,
                    writeConnNum, readConnNum, dbUrl);
            builder.slaves(this.slaves);
            builder.sharding(shardStart, shardEnd);

            return builder;
        }

        @Override
        DBType type() {
            return DBType.MYSQL;
        }
    }
    
    static class PostgreDB extends JdbcDB {
        protected PostgreDB(int dbNo, int level, int shardingStart, int shardingEnd,
                int writeConnNum, int readConnNum, String dbUrl, NodeAddress[] slaves, String mode,
                String account, String pwd) {
             super(dbNo, level, shardingStart, shardingEnd, writeConnNum, readConnNum, dbUrl, slaves, mode, account, pwd);
        }
        
        @Override
        AbsRDBWBuilder getBuilder(ServiceInfo si, int cid, String service, String db) {
            PostgreBuilder builder = new PostgreBuilder(cid, service, db,
                    account, pwd,
                    writeConnNum, readConnNum, dbUrl);
            builder.slaves(this.slaves);
            builder.sharding(shardStart, shardEnd);

            return builder;
        }

        @Override
        DBType type() {
            return DBType.POSTGRE;
        }
    }
    
    static class OracleDB extends JdbcDB {
        protected OracleDB(int dbNo, int level, int shardingStart, int shardingEnd,
                int writeConnNum, int readConnNum, String dbUrl, NodeAddress[] slaves, String mode,
                String account, String pwd) {
             super(dbNo, level, shardingStart, shardingEnd, writeConnNum, readConnNum, dbUrl, slaves, mode, account, pwd);
        }
        
        @Override
        AbsRDBWBuilder getBuilder(ServiceInfo si, int cid, String service, String db) {
            OracleBuilder builder = new OracleBuilder(cid, service, db,
                    account, pwd,
                    writeConnNum, readConnNum, dbUrl);
            builder.slaves(this.slaves);
            builder.sharding(shardStart, shardEnd);

            return builder;
        }

        @Override
        DBType type() {
            return DBType.ORACLE;
        }
    }
}
