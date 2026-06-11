package cn.net.zhijian.mesh.dbworker;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConfig.Encoding;
import org.sqlite.SQLiteConfig.JournalMode;
import org.sqlite.SQLiteConfig.SynchronousMode;
import org.sqlite.SQLiteConfig.TempStore;
import org.sqlite.SQLiteConfig.TransactionMode;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteDataSource;

import cn.net.zhijian.fileq.FQException;
import cn.net.zhijian.fileq.FQTool;
import cn.net.zhijian.fileq.FileQueue;
import cn.net.zhijian.fileq.intf.IMessage;
import cn.net.zhijian.fileq.intf.IMessageHandler;
import cn.net.zhijian.fileq.intf.IReader;
import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsConnection;
import cn.net.zhijian.mesh.frm.abs.AbsDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsJDBCWorker;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.oss.AbsOssClient;
import cn.net.zhijian.mesh.oss.AbsOssClient.UrlRequest;
import cn.net.zhijian.mesh.pool.IResourceFactory;
import cn.net.zhijian.mesh.pool.ResourcePool;
import cn.net.zhijian.mesh.server.TimerKeeper;
import cn.net.zhijian.mesh.server.TimerKeeper.TimerTaskWrapper;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.DateUtil.PeriodType;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.IUtil;
import cn.net.zhijian.util.KVPair;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.EncryptionMethod;

/**
 * 
 * 通过在建表语句中插入数据复制所需的字段，并修改增删改sql语句，实现数据增量复制。
 * 当前版本实现了对ddl、dml语句的更改，尚未实现数据增量同步与全量异地备份。
 * DBWrapper 不支持分库，需要业务自己在接口中实现
 * @author flyinmind of csdn.net
 * sqlite数据损坏主要场景
 * @see <a href="https://www.cnblogs.com/lvdongjie/p/10649308.html"></a>
 * @see <a href=https://blog.csdn.net/wjb123sw99/article/details/89463307"></a>
 */
public final class SqliteWorker extends AbsJDBCWorker {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String TIMER_DB_BACKUP = "db_backup";

    private static final String BAK_ZIP_DIR = "_zip_";
    private static final String BAK_ZIP = "dbs_bak.zip";
    private static final String BAK_HEAD = "backup_BDB_";
    private static final String BAK_DBS_DIR = "bak_dbs";
    
    //数据库每日备份时间点，UTC时间从00:00开始的分钟数，UTC向前偏移1天表示不备份
    private static final int NULL_BACKUPAT = -1;
    
    private static final int MAX_ZIP_FILE_SIZE = 16 * 1024 * 1024;

    private static final int SQLITE_VER = 3;
    private static final String SYNC_QUEUES_DIR = "sync_queues";
    private static final char SPLITER = ';';
    private static final String BAK_CONSUMER = "backup_sync";

    private static volatile int CheckBackupListTime = 0;
    private static volatile KVPair<Integer, Integer>[] BackupList = null;
    
    public final String dbFile;
    private final String poolName; //连接池名称
    private final SqliteBuilder builder;
    private final int dbNo;
    private final String dbDir;
    String bakDbFile;
    private ResourcePool<SQLiteConn> writeConnPool; //写连接池
    private ResourcePool<SQLiteConn> readConnPool; //读连接池
    private FileQueue fq;

    private volatile ResourcePool<SQLiteConn> bakConnPool = null; //备份库写连接池

    /**
     * @param dbNo webdb实例编号
     * @param builder 构建器
     * @throws SQLException 数据库异常
     * @throws FQException 队列异常
     */
    private SqliteWorker(int dbNo, SqliteBuilder builder) throws SQLException, FQException, MeshException {
        super(builder);
        this.builder = builder;
        this.dbNo = dbNo;
        String dbDir = SqliteBuilder.dbDir(builder.cid, builder.root, builder.service);
        if(!FileUtil.createDir(dbDir)) {
            throw new SQLException("Can't create dir " + dbDir);
        }
        this.dbDir = dbDir;
        this.dbFile = FileUtil.addPath(dbDir, dbName + ".db");
        this.poolName = service + '_' + dbName;

        open();
    }

    private void open() throws SQLException, FQException, MeshException {
        if(this.state() == State.NORMAL) {
            return;
        }

        NodeAddress[] slaves = builder.slaves();
        LOG.debug("{}.{} backup state:{},slaves:{}",
                service, dbName, builder.backup, NodeAddress.join(slaves, ","));

        //备份与同步相关的初始化，如果启动了同步，则一定启动备份
        //每次启动时，将db文件全部拷贝覆盖到备份目录，同时删除全部队列文件
        if(builder.backup) {
            fq = createQueue(builder.cid, builder.root);
            //有slave时才会创建消费队列，用于同步，设置时如果无slave，backup应为false
            //但是无slaves，仍然可以启动备份，因为每日备份，需要压缩备份，然后存到至简网格中
            if(slaves != null) {
                for(NodeAddress slave : slaves) {
                    String name = slave.addr.replace(':', '@').replace('.', '_'); //要做文件名，不能有冒号与点号
                    addConsumer(name, FileQueue.InitPosition.CUR,
                            new SyncQueueHandler(builder.si, cid, slave, service, dbName, dbNo));
                }
            }

            String backupDir = initBackup(builder.root, dbDir, builder.cid);
            bakDbFile = FileUtil.addPath(backupDir, dbName + ".db");
            LOG.debug("Create backup db file `{}`", bakDbFile);
            bakConnPool = new ResourcePool<>(poolName + "_bak", 1, 3000, new RwConnectionFactory(bakDbFile));
        } else {
            bakDbFile = null;
            fq = null;
            bakConnPool = null;
        }

        File f = new File(this.dbFile);
        if(f.exists()) {
            state(State.NORMAL);
            LOG.debug("Open database `{}`, dbfile already exists", dbFile);
        } else {//文件不存在，则数据库没有初始化
            if(!builder.createIfAbsent()) {
                throw new SQLException("Database `" + dbFile + "` not exists");
            }
            LOG.info("Database `{}` doesn't exist,try to create it", dbFile);
            RwConnectionFactory rwFactory = new RwConnectionFactory(this.dbFile);
            try (SQLiteConn conn = rwFactory.create()) {
                if(conn == null || conn.closed()) {
                    throw new SQLException("Fail to create database `" + dbFile + "`");
                }
                LOG.debug("Success to create database `{}`", dbFile);
            } //临时conn用完就关闭
            state(State.NOT_INIT);
        }
        readConnPool = new ResourcePool<>(poolName + "_ro", builder.readConnNum, 1000, new RoConnectionFactory(this.dbFile));
        writeConnPool = new ResourcePool<>(poolName + "_rw", builder.writeConnNum, 3000, new RwConnectionFactory(this.dbFile));
        initSysTables();

        state(State.NORMAL);
    }

    /**
     * 创建本地文件队列，用于同步数据到其他节点，或者其他实例
     * @param cid 公司id
     * @param dir 队列文件存放路径
     * @return 本地文件队列
     * @throws FQException 异常
     */
    private FileQueue createQueue(int cid, String dir) throws FQException {
        if(!FQTool.started()) {
            ThreadFactory threadFactory = new IThreadPool.NamedThreadFactory("db_sync_dispatcher");
            ExecutorService threadPool = Executors.newCachedThreadPool(threadFactory);
            FQTool.start(threadPool);
        }

        String queueDir = FileUtil.addPath(dir, SYNC_QUEUES_DIR, Integer.toString(cid), service);
        FileQueue.Builder builder = new FileQueue.Builder(queueDir, dbName)
                .maxFileNum(50)
                .maxFileSize(16 * 1024 * 1024) //16M
                .bufferedPush(false)
                .bufferedPoll(true);
        LOG.debug("Create db sync queue `{}.{}`", queueDir, dbName);

        return FQTool.create(builder);
    }
    
    /**
     * 如果启动了同步，除了给每个slave创建消费者以外，还可以用这个函数添加额外的消费者
     * @param name 名称
     * @param handler 处理器
     * @throws FQException 异常
     */
    void addConsumer(String name, FileQueue.InitPosition pos, IMessageHandler handler) throws FQException {
        if(fq != null) {
            fq.addConsumer(name, true, pos, false, handler);
        }
    }

    void addConsumer(String name, IMessageHandler handler) throws FQException {
        addConsumer(name, FileQueue.InitPosition.CUR, handler);
    }

    /**
     * Copy all `service.*` to backup dir,
     * Seek to the end of the queue to ignore all histories.
     * @param dbRoot rootDir of dbs
     * @param srcDbDir Source directory
     * @throws FQException file queue exception
     */
    private String initBackup(String dbRoot, String srcDbDir, int cid) throws FQException {
        String backupDir;
        if(cid > LOCAL_COMPANY_ID) {
            backupDir = FileUtil.addPath(dbRoot, BAK_DBS_DIR, Integer.toString(cid), service);
        } else {
            backupDir = FileUtil.addPath(dbRoot, BAK_DBS_DIR, service);
        }
        FileUtil.createDir(backupDir);
        FileUtil.copyDir(new File(srcDbDir), new File(backupDir), Pattern.compile("^" + dbName + "\\..*$"));

        //因为copy了最新的库，老的无需同步，所以位置用end
        addConsumer(BAK_CONSUMER, FileQueue.InitPosition.END, (msg, reader) -> {
            if(bakConnPool == null) {
                reader.confirm(false);
                LOG.warn("Fail to save backup in({}.{}.{}),bakConnPool is null", service, dbName, cid);
                return false;
            }

            String s = new String(msg.message(), 0, msg.len(), IUtil.DEFAULT_CHARSET);
            String[] sqls = StringUtil.split(s, SPLITER, SQL_QUOTE, true);
            boolean ok = false;

            try (AbsConnection conn = bakConnPool.get()) {
                beginTransaction(conn);
                try {
                    executeRawDML(conn, sqls);
                    ok = true;
                } catch (SQLException e) {
                    LOG.error("Fail to execute `{}` in {}.{}.{}", s, service, dbName, cid, e);
                } finally {
                    endTransaction(conn, ok);
                }
            } catch(MeshException se) {
                LOG.error("Fail to open connection of db {}.{}.{}", service, dbName, cid, se);
            } finally {
                reader.confirm(ok);
            }

            return ok;
        });

        return backupDir;
    }

    @Override
    public synchronized void close() {
        State state = this.state();
        if(state == State.CLOSED || state == State.CLOSING) {
            return;
        }
        this.state(State.CLOSING);
        readConnPool.close();
        readConnPool = null;
        //先关闭所有读连接，只留下写连接，再合并；合并完成后再关闭
        mergeFile(writeConnPool, this.dbFile);
        writeConnPool = null;

        if(fq != null) { //要放在close之后，因为close过程中可能还有写入在执行
            FQTool.remove(fq.name);
        }

        if(bakConnPool != null) {
            mergeFile(bakConnPool, this.bakDbFile);
            bakConnPool = null;
        }

        //backup(db, this.dbFile + ".bak");
        this.state(State.CLOSED);
        LOG.info("DB {}.{}, file:{} closed", service, dbName, dbFile);
    }

    private void mergeFile(ResourcePool<SQLiteConn> pool, String dbFile) {
        LOG.info("Begin merge file `{}`", dbFile);
        pool.close();
        //打开后，立刻关闭，用以合并wal、journal文件
        new CombineConnectionFactory(dbFile);
    }
     
//    public void backup(DB db, String destFile) {
//        int rc = 0;
//        try {
//            rc = db.backup("main", destFile, new ProgressObserver() {
//                @Override
//                public void progress(int remaining, int pageCount) {
//                    LOG.info("Backup {}, remaining:{}, pageCount:{}", dbFile, remaining, pageCount);
//                }
//            });
//        } catch (SQLException e) {
//            LOG.error("Fail to backup {} to {}", this.dbFile, destFile, e);
//            return;
//        }
//        LOG.info("{} to backup {} to {}, result is {}", rc == 0 ? "Success" : "Failed",
//                this.dbFile, destFile, rc);
//    }
    
    public boolean beginBackup() {
        if(bakDbFile == null) {
            LOG.info("{}.{}.{} bakDbFile is null", cid, service, dbName);
            return false;
        }
        if(bakConnPool == null) {
            LOG.error("{}.{}.{} bakConnPool is null", cid, service, dbName);
            return false;
        }
        
        fq.pauseConsumer(BAK_CONSUMER);
        // 合并数据库日志，然后关闭数据库连接，等待压缩上传
        mergeFile(bakConnPool, this.bakDbFile);
        bakConnPool = null;
        return true;
    }

    public void endBackup() {
        if(bakDbFile == null) { //无备份
            return;
        }
        fq.continueConsumer(BAK_CONSUMER);
        try { //备份完毕，恢复连接
            bakConnPool = new ResourcePool<>(poolName, 1, 3000, new RwConnectionFactory(bakDbFile));
        } catch (MeshException e) {
            LOG.error("Fail to restore backup connection to {}", bakDbFile, e);
        }
    }

    @Override
    public AbsConnection getWriteConn() throws MeshException {
        return writeConnPool.get();
    }

    @Override
    public AbsConnection getReadConn() throws MeshException {
        return readConnPool.get();
    }
    
    AbsConnection getBackupConn() throws MeshException {
        return bakConnPool.get();
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
        String alterSql = "alter table " + table + " add column " + sql.substring(pExists);

        pExists = blankSql.indexOf("exists", pAdd) + 6/*exists.length*/ + 1;
        int p = blankSql.indexOf(' ', pExists); //exists后面跟着的是表名
        String column = blankSql.substring(pExists, p).trim();
        
        try(Statement stmt = ((Connection)conn.get()).createStatement();
            ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while(rs.next()) { //cid,name,type,notnull,dflt_value,pk
                String col = rs.getString(2); //第一项编号为1
                if(col.equalsIgnoreCase(column)) {
                    return true; //字段已存在，不必执行
                }
            }
            stmt.executeUpdate(alterSql); //字段不存在，也要在此函数中执行，因为外面不知道alterSql
            return true;
        } catch (SQLException e) {
            LOG.error("Fail to get table({}) columns", table, e);
            return false;
        }
    }

    public boolean remove() {
        LOG.debug("remove db instance {} of {}.{}", dbFile, service, dbName);
        close();
        return FileUtil.remove(new File(dbFile));
    }

    @Override
    public void sync(List<String> sqls) {
        if(this.fq == null) {
            return;
        }

        String s;
        if(sqls.size() == 1) {
            s = sqls.get(0);//不可改变其内容
        } else {
            int count = 0;
            StringBuilder sb = new StringBuilder(4096);

            for(String sql : sqls) {
                if(sql == null) {
                    continue;
                }
                if(count > 0) {
                    sb.append(SPLITER);
                }
                count++;
                sb.append(sql);
            }
            if(count == 0) {
                return;
            }
            s = sb.toString();
        }

        try {
            this.fq.push(s.getBytes(IConst.DEFAULT_CHARSET));
        } catch (FQException e) {
            LOG.error("Fail to call sync {}.{}", service, dbName, e);
        }
    }

    @Override
    public void sync(String sql) {
        if(this.fq == null) {
            return;
        }
        try {
            this.fq.push(sql.getBytes(IConst.DEFAULT_CHARSET));
        } catch (FQException e) {
            LOG.error("Fail to call sync {}.{}", service, dbName, e);
        }
    }

    @Override
    public String toString() {
        return super.toString() + ",dbFile:" + dbFile;
    }

    /**
     * SQLite数据库构造器
     * @author flyinmind of csdn.net
     *
     */
    public static class SqliteBuilder extends AbsRDBWBuilder {
        private final Logger LOG = LogUtil.getInstance();
        private final ServiceInfo si; //实列运行于其中的服务

        private boolean backup = false; //是否备份
        final String root; //运行的根路径

        /**
         * 
         * @param si 运行于其中的服务
         * @param service 数据库所属的服务名称
         * @param db 数据库名称
         * @param root 数据库运行的根路径
         */
        public SqliteBuilder(ServiceInfo si, int cid, String service, String db,
                int writeConnNum, int readConnNum, String root) {
            super(cid, service, db, writeConnNum, readConnNum);
            this.si = si;
            this.root = root;
        }

        static String dbDir(int cid, String root, String service) {
            if(cid > LOCAL_COMPANY_ID) {
                return FileUtil.addPath(root, DATABASE_DIR, Integer.toString(cid), service);
            } //本地数据库
            return FileUtil.addPath(root, DATABASE_DIR, service);
        }

        /**
         * 是否启动每日备份
         * @param backup true为备份
         * @return 对象本身
         */
        public SqliteBuilder backup(boolean backup) {
            this.backup = backup;
            return this;
        }

        @Override
        public SqliteBuilder slaves(NodeAddress[] slaves) {
            if(slaves != null && slaves.length > 0) {
                this.backup = true; //如果有同步，则一定要启动备份
            }
            super.slaves(slaves);
            return this;
        }

        @Override
        public SqliteWorker build(int dbNo) {
            try {
                return new SqliteWorker(dbNo, this);
            } catch(Exception e) {
                LOG.error("Fail to create db({}.{}.{},cid:{}) under {}", service, db, dbNo, cid, root, e);
                return null;
            }
        }
    }

    /**
     * 创建本地数据库实例
     * @param si 运行于其中的服务
     * @param db 数据库名称
     * @return 数据库工人
     */
    public static AbsRDBWorker localInstance(ServiceInfo si, String db, NodeAddress[] slaves) {
        AbsDBWorker dbWorker = si.getLocalDBWorker(IDBConst.RDB, db);
        if(dbWorker != null) { //已存在，则直接返回，一个服务只会在一个线程执行，所以无需考虑并发
            if(dbWorker instanceof AbsRDBWorker) {
                return (AbsRDBWorker)dbWorker;
            }
            LOG.error("Fail to get rdbWorker {}.{},wrong type", si.name, db);
            return null;
        }
        
        SqliteBuilder builder = new SqliteBuilder(si, LOCAL_COMPANY_ID, si.name, db,
                1, DEFAULT_MAX_CONN_NUM, si.homeDir);
        builder.createIfAbsent(true); //不存在则创建
        builder.longToStr(false);
        builder.slaves(slaves);

        return builder.build(LOCAL_DBNO);
    }

    /**
     * 每分钟调用一次，只有到点的公司才会执行备份
     * 1）断开数据库同步队列，此操作不影响主库的读写操作；
     * 2）将数据库文件加密压缩到文件中，分片大小为10M；
     * 3）上传压缩文件到公有云对象存储中；
     * 4）恢复数据库同步队列消费，备份期间未同步的数据会补上。
     * 此过程是耗时操作，需要放在空闲时执行。
     * @param si webdb服务信息
     * @param force 是否强制备份
     * @return 压缩后的文件列表，用于请求put url
     * 
     */
    public static CompletableFuture<HandleResult> backup(ServiceInfo si, boolean force) {
        int cur = (int)(System.currentTimeMillis() / MINUTE_MS);
        if(cur >= CheckBackupListTime) {
            updateBackupInfo();
            CheckBackupListTime = cur + DAY_MINUTES; //一天之后再检查一次
        }

        if(BackupList == null) {
            return HandleResult.future(RetCode.OK, "no database need backup");
        }

        if(!force) {
            int taskNum = 0;
            for(KVPair<Integer, Integer> bak : BackupList) {
                if(bak.val <= cur) {
                    taskNum++;
                }
            }
            
            if(taskNum == 0) { //此处退出，避免每分钟执行一次目录操作
                return HandleResult.future(RetCode.OK, "no database need backup");
            }
        }
        
        String dbHomeDir = AbsPlatform.dbRoot();
        File bakHome = new File(FileUtil.addPath(dbHomeDir, BAK_DBS_DIR));
        File bakZipDir = new File(FileUtil.addPath(bakHome, BAK_ZIP_DIR));
        LOG.debug("Clear dir `{}`", bakZipDir);
        FileUtil.clearDir(bakZipDir);//如果压缩目录已经存在，则清除，如果目录不存在，则创建它

        Map<Integer, CompletableFuture<HandleResult>> tasks = new HashMap<>();
        for(KVPair<Integer, Integer> bak : BackupList) {
            if(bak.val <= cur || force) {
                if(!force) {//定时同步的情况，时间后移一天，强制同步则不需要
                    bak.val += DAY_MINUTES;
                }
                File f = new File(FileUtil.addPath(bakHome, Integer.toString(bak.key)));
                tasks.put(bak.key, backupOneCompany(si, bak.key, f, bakZipDir));
            }
        }

        Collection<CompletableFuture<HandleResult>> taskArr = tasks.values();
        return CompletableFuture.allOf(taskArr.toArray(new CompletableFuture[0])).thenApplyAsync(o -> {
            HandleResult hr;
            for(Map.Entry<Integer, CompletableFuture<HandleResult>> f : tasks.entrySet()) {
                try {
                    hr = f.getValue().get();
                    LOG.info("Backup data of company({}),result:{}", f.getKey(), hr.brief());
                } catch (InterruptedException | ExecutionException e) {
                }
            }
            return HandleResult.OK;//只记录失败，最终都返回成功
        }, IThreadPool.Pool);
    }
    
    private static CompletableFuture<HandleResult> backupOneCompany(int cid, String companyToken, String keystoreToken, File comDbBackHome, File bakZipDir) {
        //产生随机密码，并使用数据根密钥加密后传到服务端保存
        String pwd = StringUtil.base64UUID();
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_KEYSTORE)
                .traceId(IConst.SERVICE_KEYSTORE + '_' + cid)
                .cid(cid)
                .url("/encode?pwd=" + pwd + "&cid=" + cid)
                .token(keystoreToken)
                .nodeId(cid);
        //必须请求本地的keystore服务，所以备份与恢复都是公司已注册或登录，并且服务已启动的情况
        //密码是使用数据根密钥加解密的，而数据根密钥在云侧有备份，本地丢失也可以恢复
        return ServiceClient.serviceGet(req).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to encode pwd of company({}),result:{}", cid, hr.brief());
                return CompletableFuture.completedFuture(hr);
            } 
            String encodedPwd = ValParser.getAsStr(hr.data, "pwd");
            innerBeginBackup(cid);
            char[] pwdArr = pwd.toCharArray();
            return backupZip(cid, pwdArr, encodedPwd, companyToken, comDbBackHome, bakZipDir).whenCompleteAsync((r, e) -> {
                if(e != null) {
                    LOG.error("backupZip(cid:{},dir:{}),failed", cid, bakZipDir, e);
                } else {
                    LOG.info("backupZip(cid:{},dir:{}),result:{}", cid, bakZipDir, r);
                }
                SecureUtil.clearPassword(pwdArr);
                innerEndBackup(cid);
            }, IThreadPool.Pool);
        }, IThreadPool.Pool);        
    }
    
    private static CompletableFuture<HandleResult> backupOneCompany(ServiceInfo si, int cid, File comDbBackHome, File bakZipDir) {
        LOG.debug("backupOneCompany {},{}", si.name, cid);
        return getAdminToken(si, cid, new String[]{IConst.SERVICE_COMPANY, IConst.SERVICE_KEYSTORE}).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to get company&keystore admin token of company({}),result:{}", cid, hr.brief());
                return CompletableFuture.completedFuture(hr);
            }
            String companyToken = ValParser.getAsStr(hr.data, IConst.SERVICE_COMPANY);
            String keystoreToken = ValParser.getAsStr(hr.data, IConst.SERVICE_KEYSTORE);
            return backupOneCompany(cid, companyToken, keystoreToken, comDbBackHome, bakZipDir);
        }, IThreadPool.Pool).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to backup db of company({})", cid, e);
            }
        }, IThreadPool.Pool);
    }
    
    private static void innerBeginBackup(int cid) {
        for(Map.Entry<String, AbsRDBWorker> db : DBWorkers.entrySet()) {
            AbsRDBWorker dw = db.getValue();
            if(dw == null) {
                LOG.error("innerBeginBackup({}) is null", db.getKey());
                continue;
            }
            if(dw.cid == cid && (dw instanceof SqliteWorker)) {
                LOG.info("innerBeginBackup({})", db.getKey());
                ((SqliteWorker)dw).beginBackup();//停止同步队列，并关闭备库，准备压缩上传
            }
        }
    }

    private static void innerEndBackup(int cid) {
        for(Map.Entry<String, AbsRDBWorker> db : DBWorkers.entrySet()) {
            AbsRDBWorker dw = db.getValue();
            if(dw == null) {
                LOG.error("innerEndBackup({}) is null", db.getKey());
                continue;
            }
            if(dw.cid == cid && (dw instanceof SqliteWorker)) {
                LOG.info("innerEndBackup({}, {})", db.getKey(), cid);
                ((SqliteWorker)dw).endBackup(); //打开备库，并恢复同步队列
            }
        }
    }

    private static void innerBeginRestore(int cid, String dstDir) {
        for(Map.Entry<String, AbsRDBWorker> db : DBWorkers.entrySet()) {
            AbsRDBWorker dw = db.getValue();
            if(dw == null) {
                LOG.error("innerBeginRestore({}) is null", db.getKey());
                continue;
            }
            if(dw.cid == cid) {
                LOG.info("innerBeginRestore({})", db.getKey());
                try {
                    dw.close();
                } catch (IOException e) {
                    LOG.error("Fail to close db({}) before restoring", db.getKey(), e);
                }
            }
        }
        FileUtil.clearDir(new File(dstDir)); //清除数据库目录，用zip文件解压覆盖
    }

    private static void innerEndRestore(int cid) {
        for(Map.Entry<String, AbsRDBWorker> db : DBWorkers.entrySet()) {
            AbsRDBWorker dw = db.getValue();
            if(dw == null) {
                LOG.error("AbsRDBWorker({}) is null", db.getKey());
                continue;
            }
            if(dw.cid == cid) {
                LOG.info("innerEndRestore({})", db.getKey());
                try {
                    ((SqliteWorker)dw).open();
                } catch (Exception e) {
                    LOG.error("Fail to open db({}) after restoring", db.getKey(), e);
                }
            }
        }
    }
    //-------------------------------------------------------------------------
    /**
     *
     * @param cid 公司id
     * @param pwd 原密码，用于加密数据压缩文件
     * @param encodedPwd 加密后的密码，用于备份在公司服务的oss库中
     * @param companyToken 备份token
     * @param comDbBakHome 包括cid号的备份路径
     * @param bakZipDir 存放压缩包的路径
     * @return 异步结果
     */
    private static CompletableFuture<HandleResult> backupZip(int cid, char[] pwd, String encodedPwd, String companyToken, File comDbBakHome, File bakZipDir) {
        LOG.debug("Prepare to generate backup zip");
        String[] fileList = comDbBakHome.list();
        if(fileList == null || fileList.length == 0) {
            LOG.debug("No database files under `{}`,needn't backup", comDbBakHome);
            return HandleResult.future();
        }
        
        ZipParameters zipParameters = new ZipParameters();
        zipParameters.setEncryptFiles(true);
        zipParameters.setIncludeRootFolder(false);
        zipParameters.setEncryptionMethod(EncryptionMethod.AES);
        zipParameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        
        String zfName = FileUtil.addPath(bakZipDir, Integer.toString(cid) + '_' + BAK_ZIP);
        LOG.debug("Compress files to `{}` with password", zfName);
        try(ZipFile zf = new ZipFile(zfName, pwd)) {
            zf.createSplitZipFileFromFolder(
                comDbBakHome,
                zipParameters,
                true,
                MAX_ZIP_FILE_SIZE
            );
            
            List<Map<String, Object>> objs = new ArrayList<>();
            for(File f : zf.getSplitZipFiles()) {
                Map<String, Object> obj = new HashMap<>();
                obj.put("name", f.getName());
                obj.put("md5", FileUtil.digest(f));
                obj.put("size", f.length());
                objs.add(obj);
            }

            ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
                    .traceId(IConst.SERVICE_COMPANY + '_' + cid)
                    .cid(cid)
                    .url("/oss/dbputurl")
                    .put("objs", objs)
                    .put("service", "backup")
                    .put("type", "BDB")
                    .put("pwd", encodedPwd)
                    .token(companyToken)
                    .nodeId(cid);

            return ServiceClient.cloudPost(req).thenApplyAsync(hr -> {
                if(hr.code != RetCode.OK) {
                    LOG.error("Fail to get put-urls of {}.{},result:{}", cid, zfName, hr.brief());
                    return hr;
                }
                List<Object> urls = ValParser.getAsList(hr.data, "urls");
                if(urls == null || urls.isEmpty()) {
                    LOG.error("Fail to get put-urls of {}.{},no urls", cid, zfName);
                    return new HandleResult(RetCode.NO_RIGHT, "backup not opened");
                }
                return uploadBaks(cid, bakZipDir, urls);
            }, IThreadPool.Pool).whenCompleteAsync((hr, e) -> {
                long backupTime = System.currentTimeMillis();
                if(e != null) {
                    LOG.error("Fail to upload backup files of {}.{}", cid, zfName, e);
                    reportBackup(cid, backupTime, e.getMessage(), companyToken);
                    return;
                }
                
                if(hr.code != RetCode.OK) {
                    LOG.error("Fail to upload backup files,result:{}", hr.brief());
                }
                //无论成功失败，都报告结果
                reportBackup(cid, backupTime, hr.code + ":" + hr.info, companyToken);
            }, IThreadPool.Pool);
        } catch (Exception e) {
            LOG.error("Fail to compress backup db files", e);
            return HandleResult.future(RetCode.INTERNAL_ERROR, e.getMessage());
        }
    }
    
    /**
     * 上传多个zip文件，这些zip文件是同一个公司的db压缩后分包所得
     * 大部分情况，只有一个文件
     * @param cid 公司ID
     * @param bakZipDir 压缩备份路径，bak_dbs/_zip_
     * @param urls 上传URL
     * @return 结果
     */
    private static HandleResult uploadBaks(int cid, File bakZipDir, List<Object> urls) {
        CountDownLatch counter = new CountDownLatch(urls.size());
        List<String> failed = new ArrayList<>();
        String objHead = SecureUtil.md5(BAK_HEAD + cid) + '_';
        long size = 0;
        
        for(Object o : urls) {
            Map<String, Object> map = ValParser.parseObject(o);
            UrlRequest req = UrlRequest.fromMap(map);
            if(!req.objName.startsWith(objHead)) {
                LOG.error("Invalid uploadBaks file `{}`, objName({}) wrong", req.url, req.objName);
                return new HandleResult(RetCode.DATA_WRONG, "invalid object name");
            }
            String locName = req.objName.substring(objHead.length());
            File locFile = new File(FileUtil.addPath(bakZipDir, locName));
            size += req.size;
            LOG.debug("Upload `{}` to `{}`", locFile, req.url);
            AbsOssClient.upload(req, locFile).whenCompleteAsync((hr, e) -> {
                if(e != null) {
                    LOG.error("Fail to upload `{}` to `{}`", locFile, req.url, e);
                    failed.add(req.url);
                } else if(hr.code != RetCode.OK) {
                    LOG.error("Fail to upload `{}` to `{}`, result:{}", locFile, req.url, hr.brief());
                    failed.add(req.url);
                } else if(LOG.isDebugEnabled()) {
                    LOG.debug("Success to upload `{}` to `{}`", locFile, req.url);
                }
                counter.countDown();
            }, IThreadPool.Pool);
        }
        
        try {
            counter.await(AbsOssClient.predictedHttpTime(size), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
        }
        if(!failed.isEmpty()) {
            return new HandleResult(RetCode.INTERNAL_ERROR, StringUtil.concat(failed, ";"));
        }
        return HandleResult.OK;
    }
    
    /**
     * 从云端下载数据库备份，并在本地解压。
     * 服务器必须处于停止状态才可以恢复数据，重启后才可以使得所有数据最终生效。
     * 1）断开数据库同步队列，此操作不影响主库的读写操作；
     * 2）下载云端压缩后的数据文件；
     * 3）解压数据文件到数据库工作目录与备份目录，并清除队列文件；
     * 4）恢复数据库同步队列消费，备份期间未同步的数据会补上。
     * 此过程是耗时操作，需要放在空闲时执行。
     * @param si webdb服务信息
     * @param dbHomeDir 数据库根目录
     * @param cid 公司id
     * @return 压缩后的文件列表，用于请求put url
     */
    public static CompletableFuture<HandleResult> restore(ServiceInfo si, String dbHomeDir, int cid) {
        return getAdminToken(si, cid, new String[]{IConst.SERVICE_COMPANY, IConst.SERVICE_KEYSTORE}).thenComposeAsync(thr -> {
            if(thr.code != RetCode.OK) {
                LOG.error("Fail to get AdminToken of company({}),result:{}", cid, thr.brief());
                return CompletableFuture.completedFuture(thr);
            }
            String companyToken = ValParser.getAsStr(thr.data, IConst.SERVICE_COMPANY);
            String keystoreToken = ValParser.getAsStr(thr.data, IConst.SERVICE_KEYSTORE);
            String comDbHome = FileUtil.addPath(dbHomeDir, DATABASE_DIR, Integer.toString(cid));
            String bakZipDir = FileUtil.addPath(dbHomeDir, BAK_DBS_DIR, BAK_ZIP_DIR);

            ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
                .url("/oss/dbgeturl")
                .appendPara("service", "backup")
                .appendPara("type", "BDB")
                .traceId(IConst.SERVICE_COMPANY + '_' + cid)
                .token(companyToken)
                .cid(cid);

            return ServiceClient.cloudGet(req).thenComposeAsync(hr -> {
                if(hr.code != RetCode.OK) {
                    LOG.error("Fail to get get-urls of {}.{},result:{}", cid, bakZipDir, hr.brief());
                    return CompletableFuture.completedFuture(hr);
                }
                List<Object> urls = ValParser.getAsList(hr.data, "urls");
                if(urls == null || urls.isEmpty()) {
                    return HandleResult.future(RetCode.NO_RIGHT, "backup not opened");
                }
                return downloadBaks(cid, comDbHome, bakZipDir, urls, keystoreToken);
            }, IThreadPool.Pool).whenCompleteAsync((hr, e) -> {
                if(e != null) {
                    LOG.error("Fail to get get-urls of {}.{}", cid, bakZipDir, e);
                }
            }, IThreadPool.Pool);
        }, IThreadPool.Pool);
    }

    /**
     * 逐个桶尝试，只要有一个桶下载成功，则结束
     * @param cid 公司id
     * @param comDbHome 解压后的数据库存放目录
     * @param bakZipDir 存放数据库压缩文件的目录
     * @param urls oos下载连接列表，每个元素是一个桶中的对象列表
     * @param keystoreToken 密钥库管理token
     * @return 异步结果
     */
    private static CompletableFuture<HandleResult> downloadBaks(int cid, String comDbHome, String bakZipDir, List<Object> urls, String keystoreToken) {
        CompletableFuture<HandleResult> cf = HandleResult.future(RetCode.UNKNOWN_ERROR);
        for(Object url : urls) {
            //形成一个异步链，只要有一个不成功，则后面的不执行
            cf = cf.thenComposeAsync(hr -> {
                if(hr.code == RetCode.OK) {
                    return CompletableFuture.completedFuture(hr);
                }
                List<Object> objs = ValParser.parseList(url);
                //从任意数据中心下载都可以
                return downloadFromOneBucket(cid, comDbHome, bakZipDir, objs, keystoreToken);
            }, IThreadPool.Pool);
        }
        FileUtil.clearDir(new File(bakZipDir));
        return cf;
    }

    private static void reportBackup(int cid, long backupTime, String descr, String companyToken) {
        List<Map<String, Object>> infos = List.of(
            Map.of("name", "backupAt", "val", backupTime),
            Map.of("name", "descr", "val", descr)
        );
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
                .url("/company/setextinfos")
                .put("infos", infos)
                .traceId(IConst.SERVICE_COMPANY + '_' + cid)
                .token(companyToken)
                .cid(cid);
        ServiceClient.cloudPut(req).whenCompleteAsync((hr, e) -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to report BackupTime of company{},result:{}", cid, hr.brief());
            }
        }, IThreadPool.Pool);
    }

    private static CompletableFuture<HandleResult> downloadFromOneBucket(int cid, String comDbHome, String bakZipDir, List<Object> objs, String keyStoreToken) {
        if(objs == null || objs.isEmpty()) {
            return HandleResult.future(RetCode.DB_ERROR, "no objs in company " + cid);
        }
        List<String> failed = new ArrayList<>();
        CountDownLatch counter = new CountDownLatch(objs.size());
        String objHead = SecureUtil.md5(BAK_HEAD + cid) + '_';
        long size = 0;

        for(Object o : objs) {
            Map<String, Object> map = ValParser.parseObject(o);
            UrlRequest req = UrlRequest.fromMap(map);
            if(!req.objName.startsWith(objHead)) {
                LOG.error("Invalid downloadFromOneBucket file `{}`, objName({}) wrong", req.url, req.objName);
                return HandleResult.future(RetCode.DATA_WRONG, "invalid object name");
            }
            size += req.size;
            String locName = req.objName.substring(objHead.length());
            File locFile = new File(FileUtil.addPath(bakZipDir, locName));
            AbsOssClient.download(req, locFile, req.size).whenCompleteAsync((hr, ex) -> {
                if(ex != null) {
                    LOG.error("Fail to download `{}`", req.url, ex);
                    failed.add(req.url);
                } else if(hr.code != RetCode.OK) {
                    failed.add(req.url);
                    LOG.error("Fail to download `{}`, result:{}", req.url, hr.brief());
                } else {
                    String md5 = FileUtil.digest(locFile);
                    if(md5 == null || !md5.equals(req.md5)) { //只告警，并不阻止往下执行
                        failed.add(req.url);
                        LOG.error("Invalid file `{}`,size:{}/{}, md5:'{}'!='{}'", req.url, locFile.length(), req.size, md5, req.md5);
                    } else {
                        LOG.debug("Success to download `{}`", req.url);
                    }
                }
                counter.countDown();
            }, IThreadPool.Pool);
        }
        
        try {
            counter.await(AbsOssClient.predictedHttpTime(size), TimeUnit.MILLISECONDS); //等待所有对象下载完毕，按80K/s预估时间
        } catch (InterruptedException e) {
        }

        if(!failed.isEmpty()) { //所有桶都尝试了，仍然没有下载到完整的备份压缩包
            return HandleResult.future(RetCode.DATA_WRONG, "no valid zip file of company " + cid);
        }

        Map<String, Object> one = ValParser.getAsObject(objs, 0); //must not be null
        String pwd = ValParser.getAsStr(one, "pwd");
        //同一次备份，无论压缩分包多少个，密码都一样
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_KEYSTORE)
                .traceId(IConst.SERVICE_KEYSTORE + '_' + cid)
                .cid(cid)
                .url("/decode?pwd=" + pwd + "&cid=" + cid)
                .token(keyStoreToken)
                .nodeId(cid);
        return ServiceClient.serviceGet(req).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to decode pwd of company({}),result:{}", cid, hr.brief());
                return CompletableFuture.completedFuture(hr);
            }
            String decodedPwd = ValParser.getAsStr(hr.data, "pwd");
            String zfName = FileUtil.addPath(bakZipDir, Integer.toString(cid) + '_' + BAK_ZIP);
            innerBeginRestore(cid, comDbHome);
            try(ZipFile zf = new ZipFile(zfName, decodedPwd.toCharArray())) {
                zf.extractAll(comDbHome);
                return CompletableFuture.completedFuture(HandleResult.OK);
            } catch (IOException e) {
                LOG.error("Fail to uncompress backup db files", e);
                return HandleResult.future(RetCode.DATA_WRONG, "invalid zip file");
            } finally {
                innerEndRestore(cid);
            }
        }, IThreadPool.Pool);
    }
    
    /**
     * 启动备份定时器.每个公司的备份时间点可以不一样，每5分钟检查一次,如果已到点，则启动备份
     * @param si webdb的服务信息
     */
    public static void setBackupTimer(ServiceInfo si) {
        TimerKeeper.removeTimerTask(TIMER_DB_BACKUP); //首先删除定时器，防止设置多次
        LOG.info("Start up timer {}", TIMER_DB_BACKUP);
        TimerKeeper.addTimerTask(new TimerTaskWrapper(
            TIMER_DB_BACKUP, PeriodType.CYCLE, 60000, //每分钟检查一次
            () -> backup(si, false)
        ));
    }
    
    private static void updateBackupInfo() {
        String dbHomeDir = AbsPlatform.dbRoot();
        File bakHome = new File(FileUtil.addPath(dbHomeDir, BAK_DBS_DIR));
        String cids = "";
        
        //根环境需要备份所有公司的数据库目录，私有环境，只需要备份配置过的公司
        if(PartitionConfig.instance().isPrivate()) {
            cids = Integer.toString(CompanyInfo.instance().id);
        } else {
            File[] files = bakHome.listFiles();
            if(files == null || files.length == 0) {
                LOG.debug("No db directories under {}", bakHome);
                return;
            }
    
            
            //遍历数据库目录，所有目录名称为数字的，认为是一个公司的数据库目录
            //而不是从companyinfo列表中获取公司列表，在根环境有其他公司运行在一起
            for(File f : files) {
                if(!f.isDirectory()) {
                    continue;
                }
                String name = f.getName();
                if(!name.matches("\\d+")) {
                    continue;
                }
                if(!cids.isEmpty()) {
                    cids += ',';
                }
                cids += name;
            }
        }
        LOG.debug("updateBackupInfo under {},cids:{}", bakHome, cids);
        updateBackupInfo(cids);
    }
    
    @SuppressWarnings("unchecked")
    public static void updateBackupInfo(String cids) {
        CompanyInfo ci = CompanyInfo.instance();
        if(ci == null) {
            LOG.error("No valid company info");
            return;
        }

        getBackupAtList(ci, cids).whenComplete((kv, e) -> {
            if(e != null) {
                LOG.error("Fail to get backupAt", e);
                return;
            }
            List<KVPair<Integer, Integer>> list = new ArrayList<>();
            int at;
            int next;
            int cur = (int)(System.currentTimeMillis() / MINUTE_MS);
            int weeHours = cur - (cur % DAY_MINUTES);
            
            for(Map.Entry<Integer, Integer> l : kv.entrySet()) {
                //记录对应UTC时间与0点的分钟数
                //如东八区凌晨2点备份，记(120-480+1440)=1080
                //东八区早晨9点，记为(540-480)*60
                at = l.getValue(); 
                if(at >= 0) {
                    next = weeHours + at;
                    list.add(new KVPair<>(l.getKey(), next));
                }
            }
            if(list.isEmpty()) {
                BackupList = null;
                LOG.debug("No backup needs for companies {}", cids);
            } else {
                BackupList = list.toArray(new KVPair[] {});
                if(LOG.isDebugEnabled()) {
                    for(KVPair<Integer, Integer> kp : BackupList) {
                        LOG.debug("Backup company {} at {}", kp.key, DateUtil.utcToLocale((long)kp.val * MINUTE_MS));
                    }
                }
            }
        });
    }    

    /**
     * 查询公司的备份时间点，可以查询多个
     * @param ci 公司信息
     * @param cids 在环境中运行的公司id列表，以逗号分隔
     * @return 每个公司的备份时间点
     */
    private static CompletableFuture<Map<Integer, Integer>> getBackupAtList(CompanyInfo ci, String cids) {
        ServiceReqBuilder builder = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
                .url("/webdb/getBackupAtList").appendPara("companies", cids)
                .token(ci.adminToken(IConst.SERVICE_COMPANY).generate())
                .cid(ci.id)
                .nodeId(ci.id);
        CompletableFuture<HandleResult> cf;
        if(ci.isRoot()) {
            cf = ServiceClient.serviceGet(builder); //根环境，company是本地服务
        } else {
            cf = ServiceClient.cloudGet(builder); //私有云环境，company是云上服务
        }
        return cf.thenApplyAsync(hr -> {
            Map<Integer, Integer> iKv = new HashMap<>();
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to call GetBackupAt({}),result:{}", cids, hr.brief());
                return iKv;
            }
            //返回键值对，公司id为键，备份时间点为值
            Map<String, Object> kv = ValParser.getAsObject(hr.data, "list");
            if(kv != null && !kv.isEmpty()) {
                for (Map.Entry<String, Object> e : kv.entrySet()) {
                    iKv.put(ValParser.parseInt(e.getKey(), 0), ValParser.parseInt(e.getValue(), NULL_BACKUPAT));
                }
            }
            return iKv;
        }, IThreadPool.Pool);
    }
    //-------------------------------------------------------------------------
    static class SQLiteConn extends AbsConnection {
        public SQLiteConn(SQLiteConnection conn) {
            super(conn);
        }

        @Override
        public boolean closed() {
            try {
                return ((SQLiteConnection)get()).isClosed();
            } catch (SQLException e) {
                return true;
            }
        }
        
        @Override
        public boolean test() {
            return true;
        }
    }
    
    static class RoConnectionFactory implements IResourceFactory<SQLiteConn> {
        private final SQLiteConn conn;
        
        public RoConnectionFactory(String dbFile) throws MeshException {
            SQLiteConfig cfg = new SQLiteConfig();
            try {
                cfg.setEncoding(Encoding.UTF8);
                cfg.setTempStore(TempStore.MEMORY);
                cfg.enforceForeignKeys(false);
                cfg.enableRecursiveTriggers(false);
                cfg.enableCaseSensitiveLike(true);
                //cfg.setUserVersion(SQLITE_VER);//不可以，否则在内部会调用pragma，导致只读异常
    
                cfg.setReadOnly(true);
    
                SQLiteDataSource dataSource = new SQLiteDataSource(cfg);
                dataSource.setUrl("jdbc:sqlite:" + dbFile);
                LOG.debug("Open a ro-connection to database `{}`", dataSource.getUrl());
                
                SQLiteConnection conn = (SQLiteConnection)dataSource.getConnection();
                this.conn = new SQLiteConn(conn);
            } catch(Exception se) {
                throw new MeshException("Fail to create rw connetion to " + dbFile, se);
            }
        }

        public SQLiteConn create() {
            return this.conn;
        }
    }
    
    static class RwConnectionFactory implements IResourceFactory<SQLiteConn> {
        private final SQLiteConn conn; //只能一个写连接

        public RwConnectionFactory(String dbFile) throws MeshException {
            try {
                SQLiteConfig cfg = new SQLiteConfig();
    
                cfg.setJournalMode(JournalMode.WAL);
                cfg.setEncoding(Encoding.UTF8);
                cfg.enforceForeignKeys(false);
                cfg.enableRecursiveTriggers(false);
                cfg.enableCaseSensitiveLike(true);
                cfg.setSynchronous(SynchronousMode.NORMAL);
                cfg.setTempStore(TempStore.MEMORY);
                cfg.setTransactionMode(TransactionMode.IMMEDIATE);
                cfg.setUserVersion(SQLITE_VER);
    
                cfg.setReadOnly(false);
                //cfg.setPragma(Pragma.COUNT_CHANGES, "true"); //deprecated

                SQLiteDataSource dataSource = new SQLiteDataSource(cfg);
                dataSource.setUrl("jdbc:sqlite:" + dbFile);
                SQLiteConnection conn = (SQLiteConnection)dataSource.getConnection();
                this.conn = new SQLiteConn(conn);
            } catch(Exception se) {
                throw new MeshException("Fail to create rw connetion to " + dbFile, se);
            }
        }
    
        public SQLiteConn create() {
            return this.conn;
        }
    }

    static class CombineConnectionFactory implements IResourceFactory<SQLiteConn> {
        public CombineConnectionFactory(String dbFile) {
            /*
             * JournalMode为WAL时，会产生wal、shm文件，即使关闭链接，这些文件也不会消失。
             * 这些文件特别大，不利于数据文件备份，所以在关闭之前，强制合并一次。
             * 以下代码执行一次vacuum释放已删除数据占用的空间，并强制合并wal文件，以便于备份。
             */
            SQLiteConfig cfg = new SQLiteConfig();
            cfg.setJournalMode(JournalMode.OFF);
            cfg.setSynchronous(SynchronousMode.OFF);
            cfg.setEncoding(Encoding.UTF8);
            cfg.setUserVersion(SQLITE_VER);
            
            SQLiteDataSource writeDataSource = new SQLiteDataSource(cfg);
            writeDataSource.setUrl("jdbc:sqlite:" + dbFile);
            try {
                Connection conn = writeDataSource.getConnection();
                try(Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("VACUUM"); //释放已删除数据占用的空间
                }
                conn.close();
            } catch (SQLException e) {
                LOG.warn("Fail to merge wal file of {}", dbFile, e);
            }
        }
    
        public SQLiteConn create() {
            return null;
        }
    }

    /**
     * 数据库同步queue，通过调用从数据库节点的sync接口同步写sql语句
     * @author flyinmind of csdn.net
     *
     */
    public static class SyncQueueHandler implements IMessageHandler {
        private static final String SYNC_URL = "/sync";

        private final NodeAddress node;
        private final ServiceInfo caller; //caller与callee都是webdb
        private final String token;
        private final String traceId;
        private final String service;
        private final String db;
        private final int dbNo;
        private final int cid;

        public SyncQueueHandler(ServiceInfo caller, int cid, NodeAddress node, String service, String db, int dbNo) {
            this.node = node; //new NodeAddress(service, addr, caller.version, 1);
            this.caller = caller;
            this.token = BiosClient.appToken(caller, caller.name, "*");
            this.traceId = "sync_" + node.addr;
            this.service = service;
            this.db = db;
            this.dbNo = dbNo;
            this.cid = cid;
        }

        /**
         * msg是一个sqls字符串
         * 字符串的头部有service@dbName@....
         */
        @Override
        public boolean handle(IMessage msg, IReader reader) {
            String s = new String(msg.message(), 0, msg.len(), IUtil.DEFAULT_CHARSET);
            String[] sqls = StringUtil.split(s, SPLITER, SQL_QUOTE, true);
            
            ServiceReqBuilder req = new ServiceReqBuilder(caller, caller.name)
                    .url(SYNC_URL)
                    .token(this.token)
                    .cid(cid)
                    .traceId(this.traceId)
                    .put(DB_REQ_SERVICE, this.service)
                    .put(DB_REQ_DB, this.db)
                    .put(DB_REQ_DBNO, this.dbNo)
                    .put(RDB_REQ_SQLS, sqls);

            ServiceClient.servicePost(node, req).whenCompleteAsync((hr, e) -> {
                if(e != null) {
                    if(LOG.isDebugEnabled()) {
                        LOG.error("Fail to sync `{}` to {}:`{}`", this.db, node.addr, sqls, e);
                    } else {
                        LOG.error("Fail to sync `{}` to {}:`{}`, error:{}", this.db, node.addr, sqls, e.getMessage());
                    }
                    reader.confirm(false);
                    return;
                }

                if(hr.code != RetCode.OK) {
                    LOG.error("Fail to sync `{}` to {},result:{},`{}`", this.db, node.addr, hr.brief(), sqls);
                    reader.confirm(false);
                } else {
                    reader.confirm(true); //全同步方式，必须在目的端重演成功
                }
            },
            /*
             * 使用接口处理的线程池，好处是可以增加接口线程处理数，不分散；
             * 坏处是，接口处理繁忙时，会影响数据复制
             */
            IThreadPool.Pool);

            return true;
        }
    }
}
