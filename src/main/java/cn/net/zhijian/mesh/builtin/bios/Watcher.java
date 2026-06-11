package cn.net.zhijian.mesh.builtin.bios;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.BiosDNS;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.bean.RootKeystore;
import cn.net.zhijian.mesh.bean.ServerSecurity;
import cn.net.zhijian.mesh.dbworker.ItemInfo;
import cn.net.zhijian.mesh.dbworker.SqliteWorker;
import cn.net.zhijian.mesh.dbworker.TreeDBWorker;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsConnection;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.para.ParameterInfo;
import cn.net.zhijian.mesh.frm.config.tokenchecker.TokenCheckers;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.mesh.frm.intf.IServiceWatcher;
import cn.net.zhijian.mesh.frm.method.ApiMethod;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.IPUtil;
import cn.net.zhijian.util.IPUtil.FORMAT;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.Totp;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 一个region一套bios服务，
 * 一个region中可包含多个分区(partId)，它们共用bios服务，否则不能互通。
 * 一个公司(cid)只会属于一个分区，所以status.db及config按cid区分
 * <p>
 * 每个bios节点上都要有meta、status、config三个数据库。
 * <p>
 * 三个数据库需要支撑最多10万个节点，10000个服务。
 * meta：存储的是服务的基本信息，包括公钥、调用方、数据库；
 * config：中按公司维度存储服务的配置信息；
 * status：存储服务所在的节点，及服务的状态，服务及数据库的路由依赖此库。
 * <p>
 * 1）meta数据量有限，使用treedb实现，
 * 同一时间只有一个主节点，数据会向所有其他从节点同步；
 * 主节点故障后，下一个节点会作为主节点，所以在配置时，各个节点上的顺序要一致；
 * <p>
 * 2）status.srvstatus记录所有服务节点的状态，不区分公司，数据量最大可达百万行;
 * status.dbstatus记录所有数据库的状态，数据库需要区分服务、db、分片，
 * 这些标识字段是在webdb的runconfig中配置，
 * 如果是按cid分库，在RDBProcessor中请求时，库名称后面会自动增加#cid，
 * 在rdbhandler中，如果发现数据库名有#，则拷贝标准库，再创建连接；
 * <p>
 * 3）config存储每个公司、服务的配置，数据量大，但是不常改动；
 * <p>
 * 4）status库整体上采用循环备份，所以各个实例需要保证相同的顺序；
 * @author flyinmind of csdn.net
 *
 */
public final class Watcher extends IServiceWatcher.DefaultServiceWatcher implements IDBConst {
    private static final Logger LOG = LogUtil.getInstance();

    private static final String TYPE_BIOS_META = "biosmeta";   
    private static final String TYPE_BIOS_DB = "biosdb";
    private static final String BIOS_TOKEN_CHECKER = "BIOS";
    private static final int BIOS_REPORT_INTERVAL = 300 * 1000;
    private static long foreReportTime = 0;

    private CompletableFuture<EccKeyPair> getServiceKey(ServiceInfo si, String omPwd, TreeDBWorker meta) {
        if(!PartitionConfig.instance().isMainBios()) {
            LOG.debug("Not main bios, get key from main bios");
            //主bios必须第一个启动，有了它才能做数据初始化，其他bios实例，从主bios同步
            return super.getKey(si, omPwd);
        }

        LOG.debug("Main bios, get key from meta database or create it");
        EccKeyPair biosKey;
        try {
            String itemKey = "/service/" + IConst.SERVICE_BIOS + "/key";
            String bk = meta.getValue(itemKey);
            if(!StringUtil.isEmpty(bk)) {
                biosKey = EccKeyPair.parse(bk);
            } else { //mainbios首次启动，无服务密钥对，产生一个新的
                biosKey = Ecc.instance().genKeyPair();
                bk = biosKey.toString();
                meta.put(itemKey, bk, System.currentTimeMillis());
            }
        } catch (Exception e) {
            LOG.error("Fail to create service keypair for main bios", e);
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(biosKey);
    }
    
    /**
     * bios必须是第一个启动的服务。
     * 如果所有服务不是在同一个实例启动，对此无依赖。
     * 如果所有服务在同一个实例启动，则需要依赖此顺序。
     * 所以将bios的数据库初始化部分放在beforeLoad中完成。
     * @param pwd 公司密码
     */
    @Override
    public CompletableFuture<HandleResult> beforeLoad(IServiceServer server, ServiceInfo si, String pwd) {
        String localAddr = ChannelConfig.instance().localHttpAddr();
        BiosDNS biosDns = PartitionConfig.instance().biosDNS();
        if(!biosDns.isBios(localAddr)) {
            LOG.error("Host({}) is not a bios server", localAddr);
            return HandleResult.future(RetCode.FORBIDDEN, "host is not a bios server");
        }

        /*
         * 注册处理器类型，在接口配置中要用到，注册后，type才可以选择；
         * 注册token checker类型；
         */
        AbsProcessor.register(TYPE_BIOS_META, BiosMetaHandler.class);
        AbsProcessor.register(TYPE_BIOS_DB, BiosDBHandler.class);
        TokenCheckers.register(new BiosTokenChecker(BIOS_TOKEN_CHECKER, si));
        TokenCheckers.register(new OmPwdChecker());

        //主从节点之间同步数据，只接受其他bios节点的调用
        //如果需要实现数据同步，必须提供/sync接口，并且参数只能是service/db/sqls，
        //这是在sqliteworker中预设的
        String procName = "sync";
        UrlPathInfo url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
        ApiInfo apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_POST, url, null, true);
        Sync sync = new Sync(si, apiInfo, procName);
        RequestInfo requestInfo = new RequestInfo(new ParameterInfo[] {
            new ParameterInfo.Builder("db", ParameterInfo.TYPE_STRING).setRegular("^[a-zA-Z0-9_]{1,30}$").build(),
            new ParameterInfo.Builder("sqls", ParameterInfo.TYPE_STRING).build()
        });
        ApiMethod am = ApiMethod.generate(sync, apiInfo, requestInfo, TokenCheckers.App, null);
        server.addApi(am);
        
        //设置或查询mainbios
        procName = "mainbios";
        url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
        apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, true);
        MainBios mainbios = new MainBios(si, apiInfo, procName);
        requestInfo = new RequestInfo(new ParameterInfo[] {
            new ParameterInfo.Builder("act", ParameterInfo.TYPE_STRING).build(),
            new ParameterInfo.Builder("old", ParameterInfo.TYPE_INT, false).build(),
            new ParameterInfo.Builder("next", ParameterInfo.TYPE_INT, false).build()
        });
        am = ApiMethod.generate(mainbios, apiInfo, requestInfo, TokenCheckers.AppAll, null);
        server.addApi(am);
        
        //接受主bios通知，main已经改变
        procName = "mainchanged";
        url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
        apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_PUT, url, null, true);
        MainChanged mainchanged = new MainChanged(si, apiInfo, procName);
        requestInfo = new RequestInfo(new ParameterInfo[] {
            new ParameterInfo.Builder("main", ParameterInfo.TYPE_STRING).build()
        });
        am = ApiMethod.generate(mainchanged, apiInfo, requestInfo, TokenCheckers.App, null);
        server.addApi(am);

        //查询实例的parttition.cfg内容：/bios/api/partition
        procName = "partition";
        url = new UrlPathInfo(si.name).push(SERVICE_URL_API).push(procName);
        apiInfo = new ApiInfo(SERVICE_URL_ROOT, METHOD_GET, url, null, false);
        GetPartitionConfig partition = new GetPartitionConfig(server, si, apiInfo, procName);
        am = ApiMethod.generate(partition, apiInfo, partition.getRequestInfo(), null, null);
        server.addApi(am);

        /* 
         * 启动时，在本地创建数据库，并建立同步关系。
         * db（包括status、config）的同步关系需要在partition.cfg中指定，通常建议循环备份。
         * 如果都没有设置备份库，则备份功能不会启动，本地文件队列也不会启动
         */
        String dbCfgFile = FileUtil.addPath(si.homeDir, LOCAL_DATABASE_CONFIG_FILE);
        List<Object> dbCfgs = JsonUtil.jsonFileToList(new File(dbCfgFile), true);
        if(dbCfgs == null || dbCfgs.isEmpty()) {
            LOG.error("Invalid database config file `{}`", dbCfgFile);
            return HandleResult.future(RetCode.INTERNAL_ERROR, "invalid " + LOCAL_DATABASE_CONFIG_FILE);
        }

        NodeAddress[] slaves = biosDns.slaves(localAddr);
        LOG.debug("Bios slaves:{}", NodeAddress.join(slaves, ","));
        for(Object o : dbCfgs) {
            Map<String, Object> dbCfg = ValParser.parseObject(o);
            if(createLocalDB(si, dbCfg, slaves) == null) {
                return HandleResult.future(RetCode.INTERNAL_ERROR, "no appropriate " + BiosDBHandler.DB + " configuration");
            }
        }
        
        /*
         * metadb依赖treedb实现，treedb底层是rdb，它的复制能力建立在rdb的基础之上。
         * metadb的复制关系是定死的，所有节点的写入都会向其他所有节点复制。
         * 但是同一时间，只会向第一个可用的节点写入（各实例配置顺序要一致，因为此顺序决定了切换顺序）。
         * 这种实现在网络出现脑裂时，可能会导致数据不一致；
         * 因为metadb中数据极少变更，所以，此处容忍这种缺陷。
         */
        TreeDBWorker meta = TreeDBWorker.localInstance(si, BiosMetaHandler.DB, slaves);
        if(meta == null) {
            LOG.error("Fail to create bios meta-db");
            return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to create meta db");
        }
        MainBios.setMetaDb(meta);
        MainChanged.setMetaDb(meta);
        BiosMetaHandler.setMetaDb(meta);

        si.addLocalDBWorker(meta, meta.getCoreDb());
        
        //用于加密datarootkey、codebook等信息，在KeystoreUtil中使用
        //公司一个根密钥，一旦丢失，能恢复，但是很麻烦
        //mainbios实例注册公司会产生root.keystore文件，并加密后存入根环境
        //在bios实例登录公司时，会从根环境加载根密钥，并存入root.keystore文件
        //所以每个bios实例启动前，必须执行一次sbin/command.bat company login cid pwd
        //否则，每个bios中根密钥都不同，导致根密钥加解密混乱
        try {
            RootKeystore.init(); //用于root encode&decode，如果不存在会自动创建
        } catch (Exception e) {
            LOG.error("Invalid  root key", e);
            return HandleResult.future(RetCode.INTERNAL_ERROR, "invalid root key");
        }

        PartitionConfig partCfg = PartitionConfig.instance();
        if(partCfg.isMainBios()) {
            HandleResult hr = initMeta(server.homeDir(), si, meta);
            if(hr.code != RetCode.OK) {
                return HandleResult.future(hr);
            }
        }
        ItemInfo ii = meta.putAtomic(MainBios.ITEM_MAINBIOS, localAddr, localAddr, System.currentTimeMillis());
        LOG.info("mainBios:{}", ii.val);
        partCfg.biosDNS().setMain(ii.val);
        
        if(!si.tokenWorker.isEmpty()) {
            return HandleResult.future();
        }

        //主bios必须第一个启动，有了它才能做数据初始化，其他bios实例，从主bios同步
        return getServiceKey(si, pwd, meta).thenApplyAsync(ecKey -> {
            if(ecKey == null) {
                return new HandleResult(RetCode.INTERNAL_ERROR, "no service key");
            }
            si.tokenWorker.setKey(ecKey);
            return HandleResult.OK;
        });
    }

    private HandleResult initMeta(String homeDir, ServiceInfo si, TreeDBWorker meta) {
        /* 基于treedb实现
        /common
          |_partition
          |_company
          |_route
          |_ca
        /service
         |_service1
           |_configs
           |_key -> val1
           |_callers
              |_service2->features...
           |_dbs
              |_db1
                 |_tabledef
         |_service2...
       */
        long updTime = System.currentTimeMillis();
        //存放partition、preference、route中的内容及datarootkey
        meta.createDir("/common", updTime);

        meta.createDir("/dbs", updTime); //存放数据库实例配置信息
        meta.createDir("/service", updTime);
        meta.createDir("/service/_all_", updTime); //存放所有已注册服务的基本信息

        String confPath = FileUtil.addPath(homeDir, SYS_CONF_DIR);
        String serviceKp, servicePath;
        EccKeyPair ekp;

        //service om
        servicePath = "/service/" + SERVICE_OM;
        meta.createDir(servicePath, updTime);
        meta.createDir(servicePath + "/callers", updTime);
        meta.createDir(servicePath + "/dbs", updTime);
        meta.createDir(servicePath + "/configs", updTime);
        meta.createDir(servicePath + "/keys", updTime);
        String totpKey = meta.getValue(OmPwdChecker.OM_TOTP_ITEM); //totp密钥
        //第一次登录，自动创建totp根密码
        File keyFile = new File(FileUtil.addPath(confPath, "totp.key"));
        if(StringUtil.isEmpty(totpKey)) {
            if(keyFile.exists()) { //文件存在，则读取文件中的存入数据库
                LOG.debug("Load totp key from {}", keyFile);
                totpKey = FileUtil.readFile(keyFile, IConst.DEFAULT_CHARSET);
            } else {
                LOG.debug("Generate totp key");
                totpKey = Totp.generateSecret(10);
                FileUtil.writeFile(keyFile, totpKey, IConst.DEFAULT_CHARSET);
            }
            meta.put(OmPwdChecker.OM_TOTP_ITEM, totpKey, updTime);
        } else {//覆盖原文件，以bios中为准，便于查看
            FileUtil.writeFile(keyFile, totpKey, IConst.DEFAULT_CHARSET);
        }
        
        try {
            ekp = Ecc.instance().genKeyPair();
            serviceKp = ekp.toString();
        } catch (Exception e) {
            LOG.error("Fail to create key pair", e);
            return new HandleResult(RetCode.INTERNAL_ERROR, "fail to create " + SERVICE_OM + " service key pair");
        }
        meta.putIfAbsent(servicePath + "/key", serviceKp, updTime);

        //service gateway
        servicePath = "/service/" + SERVICE_GATEWAY;
        meta.createDir(servicePath, updTime);
        meta.createDir(servicePath + "/callers", updTime);
        meta.createDir(servicePath + "/dbs", updTime);
        meta.createDir(servicePath + "/configs", updTime);
        try {
            ekp = Ecc.instance().genKeyPair();
            serviceKp = ekp.toString();
        } catch (Exception e) {
            LOG.error("Fail to create key pair", e);
            return new HandleResult(RetCode.INTERNAL_ERROR, "fail to create " + SERVICE_GATEWAY + " service key pair");
        }
        meta.putIfAbsent(servicePath + "/key", serviceKp, updTime);
        
        //service bios
        servicePath = "/service/" + SERVICE_BIOS;
        meta.createDir(servicePath, updTime);
        meta.createDir(servicePath + "/callers", updTime);
        meta.createDir(servicePath + "/dbs", updTime);
        meta.createDir(servicePath + "/configs", updTime);

        PartitionConfig pc = PartitionConfig.instance();
        meta.put("/common/partition", pc.toString(), updTime);
        meta.put("/common/company", CompanyInfo.instance().toString(), updTime);
        byte[] ca = FileUtil.readFile(ServerSecurity.configFile());
        meta.put("/common/ca", ByteUtil.bin2base64(ca), updTime);
        
        //所有webdb实例的信息{addr1:[...],addr2:[...],...}
        servicePath = "/service/" + SERVICE_WEBDB;
        meta.createDir(servicePath, updTime);
        meta.createDir(servicePath + "/callers", updTime);
           meta.createDir(servicePath + "/dbs", updTime);
           meta.createDir(servicePath + "/configs", updTime);

        File webdbFile = new File(FileUtil.addPath(confPath, "webdb.cfg"));
        if(webdbFile.exists()) {
            Map<String, Object> dbs = JsonUtil.jsonFileToMap(webdbFile, true);
            if(dbs == null) {
                return new HandleResult(RetCode.DATA_WRONG, "invalid webdb config");
            }
            if(!dbs.isEmpty()) {
                //检查之后才能存入数据库，入库前先删除原有的
                meta.removes(servicePath + "/dbs", updTime);
                List<DBCfg> cfgs;
                try {
                    cfgs = parseWebdbCfg(dbs);
                } catch (MeshException e) {
                    return new HandleResult(RetCode.DATA_WRONG, e.getMessage());
                }
                Map<String, StringBuilder> jsons = DBCfg.toJsons(cfgs); //addr -> cfgjson
                for(Map.Entry<String, StringBuilder> j : jsons.entrySet()) {
                    meta.put(servicePath + "/dbs/" + j.getKey(), j.getValue().toString(), updTime);
                }
            }
        }

        return HandleResult.OK;
    }
    
    /*
     * 解析webdb的配置。
     * 
     * 先按实例的地址分，每个实例上可以运行多个db(按dbNo区分)，
     * 每个dbNo可以按分片(0-32768)分成多个分段，每个分段可以独立地设置主备库，备库可以多个。
     * 
     * 私网环境，每个服务可以指定一个dbNo；
     * 根环境的公共服务仍然按服务指定dbNo，公司服务按公司指定dbNo；
     * 不指定则为0，所以系统中默认的dbNo为0。
     * 定时的云端备份在主库执行，备库可以用于查询，或主库故障时切换。
     */
    static List<DBCfg> parseWebdbCfg(Map<String, Object> dbs) throws MeshException {
           List<Object> l;
           List<DBCfg> cfgs = new ArrayList<>();
           String addr;

        for(Map.Entry<String, Object> e : dbs.entrySet()) {
            addr = e.getKey();
            l = ValParser.parseList(e.getValue());
            for(Object o : l) {
                Map<String, Object> c = ValParser.parseObject(o);
                if(c == null || c.isEmpty()) {
                    throw new MeshException("invalid webdb config " + addr);
                }
                cfgs.add(DBCfg.parse(addr, c));
            }
         }
        DBCfg.checkSlaves(cfgs);
        DBCfg.checkShardings(cfgs);
        return cfgs;
    }

    @Override
    public CompletableFuture<HandleResult> afterLoad(IServiceServer server, ServiceInfo si, String pwd) {
        watch(server, si); //强制检查一次服务状态，[解决在服务器更换ip后遗留的老的无效记录]
        return HandleResult.future();
    }

    private AbsRDBWorker createLocalDB(ServiceInfo si, Map<String, Object> dbCfg, NodeAddress[] slaves) {
        String name = ValParser.getAsStr(dbCfg, "name"); //name,type,version,versions
        //bios中一定使用sqlite实现，所以不做多种数据库适配，不走webdb
        AbsRDBWorker db = SqliteWorker.localInstance(si, name, slaves);
        if(db != null && db.createRDB(si.name, dbCfg)) {
            si.addLocalDBWorker(db, null);
            return db;
        }
        LOG.error("Can't find db `{}.{}` when createDB", si.name, name);
        return null;
    }

    @Override
    public CompletableFuture<HandleResult> startup(IServiceServer server, ServiceInfo si) {
        return HandleResult.future();
    }

    /**
     * 其他服务都是向bios上报状态，只有bios可以清除超时未上报的节点，
     * 如果一个节点超过2个周期会被置为无效，调用方查询时，将查不到它
     * 超过10个周期仍未上报状态，在此会被清除
     * @param server 虚拟服务器
     * @param si 服务信息
     */
    @Override
    public void watch(IServiceServer server, ServiceInfo si) {
        long cur = System.currentTimeMillis();
        if(cur - foreReportTime < 2 * BIOS_REPORT_INTERVAL) {
            return;
        }

        AbsRDBWorker db = (AbsRDBWorker)si.getLocalDBWorker(RDB, BiosDBHandler.DB);
        if(db == null) {
            LOG.error("Can't find db {}/{} when updte status", SERVICE_BIOS,BiosDBHandler.DB);
            return;
        }
        /*
         * 在每个bios实例上都运行，因为status按partition分库，
         * 所以，每个实例都只记录了部分partition的实例状态数据。
         * 如果，两个周期未更新状态，则认为实例异常，删除它的记录
         */
        try(AbsConnection conn = db.getWriteConn()) {
            long endTime;
            boolean ok = false;
            try {
                db.beginTransaction(conn);
                endTime = cur - 10 * BIOS_REPORT_INTERVAL; //超过10个周期，则删除
                db.executeRawDML(conn, "delete from srvstatus where " + SEG_UPDATETIME + '<' + endTime);
                db.executeRawDML(conn, "delete from dbstatus where " + SEG_UPDATETIME + '<' + endTime);
                
                endTime = cur - 2 * BIOS_REPORT_INTERVAL;//超过2个周期，则标识为无效
                db.executeRawDML(conn, "update srvstatus set srvstatus='E' where " + SEG_UPDATETIME + '<' + endTime);
                db.executeRawDML(conn, "update dbstatus set dbstatus='E' where " + SEG_UPDATETIME + '<' + endTime);
                ok = true;
            } catch (SQLException e) {
                LOG.error("Fail to execute reports in bios", e);
            } finally {
                db.endTransaction(conn, ok);
            }
        } catch(Exception e) {
            LOG.error("Fail to execute update status in bios", e);
        }
        foreReportTime = cur;
    }
    
    static class DBCfg {
        static final int MAX_DB_NO = 1000000000;
        final String addr;
        final int no;
        final String type;
        final int level;
        final int shardStart;
        final int shardEnd;
        final int readConn;
        final int writeConn;
        final String master;
        final String mode;
        final Set<String> slaves = new HashSet<>();
        
        DBCfg(String addr, int no, String type, int level,
              int readConn, int writeConn, int shardStart, int shardEnd,
              String master, String mode) {
            this.mode = mode.toLowerCase();
            this.addr = addr;
            this.no = no;
            this.type = type;
            this.level = level;
            this.readConn = readConn;
            this.writeConn = writeConn;
            this.shardStart = shardStart;
            this.shardEnd = shardEnd;
            this.master = master;
        }
        
        static DBCfg parse(String addr, Map<String, Object> cfg) throws MeshException {
            int    no = ValParser.getAsInt(cfg, CFG_SEG_NO, Integer.MIN_VALUE);
            if(no < 0 || no >= MAX_DB_NO) {
                throw new MeshException("invalid webdb config, invalid no " + no);
            }
            int start = ValParser.getAsInt(cfg, CFG_SEG_SHARDSTART, -1);
            int end = ValParser.getAsInt(cfg, CFG_SEG_SHARDEND, -1);
            if(start >= end) {
                throw new MeshException("invalid webdb config, shardStart>=shardEnd#" + no);
            }
            if(start < 0 || end > IDBConst.MAX_SHARDING_NUM) {
                throw new MeshException("invalid webdb config, shardStart:" + start + ",shardEnd:" + end + "#" + no);
            }

            int level = ValParser.getAsInt(cfg, CFG_SEG_LEVEL, 0);
            String master = ValParser.getAsStr(cfg, "master");
            if(!StringUtil.isEmpty(master)) {
                if (IPUtil.isValidIP(master, FORMAT.LAN.v + FORMAT.PORT.v + FORMAT.V4.v) != FORMAT.NONE) { //删除本地ip
                    throw new MeshException("invalid master address in (" + start + "," + end + ")#" + no);
                }
                if(level <= 0) {
                    throw new MeshException("invalid level of slave (" + start + "," + end + ")#" + no);
                }
            } else {
                level = 0;
            }
            String type = ValParser.getAsStr(cfg, CFG_SEG_TYPE, DBType.MYSQL.name());
            int readConn = ValParser.getAsInt(cfg, CFG_SEG_READCONN, 2);
            int writeConn = ValParser.getAsInt(cfg, CFG_SEG_WRITECONN, 1);
            if(readConn <= 0 || writeConn <= 0) {
                throw new MeshException("invalid read-write connection num of (" + start + "," + end + ")#" + no);
            }

            if(DBType.SQLITE.name().equals(type)) {
                writeConn = 1; //sqlite只能一个写连接
            }
            
            String mode = ValParser.getAsStr(cfg, CFG_SEG_MODE, DB_MASTER);
            if(mode.equals(DB_SLAVE)) { //slave模式，必须设置对应的主库
                if(StringUtil.isEmpty(master)) {
                    throw new MeshException("Invalid db config in " + addr + ",master not set in a slave node");
                }
            }
            
            return new DBCfg(addr, no, type, level, readConn, writeConn,
                             start, end, master, mode);
        }
        
        void addSlave(String slave) {
            slaves.add(slave);
        }
        
        @Override
        public String toString() {
            String s = "{\"no\":" + no + ",\"level\":" + level + ",\"type\":\"" + type
                    + "\",\"shardStart\":" + shardStart + ",\"shardEnd\":" + shardEnd
                    + ",\"mode\":\"" + mode + "\",\"readConn\":" + readConn + ",\"writeConn\":" + writeConn;
            if(!slaves.isEmpty()) {
                s += ",\"slaves\":\"" + StringUtil.joinArray(slaves, ",") + "\"";
            }
            if(!StringUtil.isEmpty(master)) {
                s += ",\"master\":\"" + master + "\"";
            }
            return s + "}";
        }
        
        /**
         * 根据从实例的master信息，在对应主实例中添加slave
         * @param cfgs 数据库配置
         * @throws MeshException mesh异常
         */
        private static void checkSlaves(List<DBCfg> cfgs) throws MeshException {
            for(DBCfg cfg : cfgs) {
                if(StringUtil.isEmpty(cfg.master)) {
                    continue;
                }
                DBCfg master = find(cfgs, cfg.master, cfg.no, cfg.shardStart, cfg.shardEnd);
                if(master == null) {
                    throw new MeshException("can't find master " + cfg.master
                            + " of(" + cfg.addr + "," + cfg.shardStart + "-" + cfg.shardEnd + ")#" + cfg.no);
                }
                //master不能再是slave，不支持间接同步，同一个分段可以同步到多个备份
                if(!StringUtil.isEmpty(master.master)) {
                    throw new MeshException("invalid master " + cfg.master + " of (" + cfg.addr + "," + cfg.shardStart + ")#" + cfg.no);
                }
                master.addSlave(cfg.addr);
            }
         }

        /**
        * 检查每个dbno的32768个分片是否都落实到数据库实例中
        * 主从是分开检查的，主实例一个分片只能在一个实例上，从分片可以在多个实例上
        * @param cfgs 数据库配置
        **/
        private static void checkShardings(List<DBCfg> cfgs) throws MeshException {
            Map<Integer, int[]> dbs = new HashMap<>();
            int[] shardings;
            int no;

            for(DBCfg cfg : cfgs) {
                //备份的变为负值，备份的分片与主实例的分片分别检查
                //可以让部分分片有多个备份，部分分片一个备份，只要所有的备份分片能完整覆盖就可以
                no = cfg.slaves.isEmpty() ? (cfg.no - MAX_DB_NO) : cfg.no; //无slaves则可能为备份库或无备份库的主库
                shardings = dbs.computeIfAbsent(no, k -> new int[IDBConst.MAX_SHARDING_NUM / Integer.SIZE]);

                for(int i = cfg.shardStart; i < cfg.shardEnd; i++) {
                    int j = i / Integer.SIZE;
                    int bitN = 1 << (i % Integer.SIZE);
                      if((shardings[j] & bitN) != 0 && no >= 0) { //主实例分片不能重叠，备份实例可以
                        throw new MeshException("invalid webdb config, duplicated sharding(" + i + ")#" + cfg.no + "," + cfg.addr);
                    }
                    shardings[j] |= bitN;
                }
            }
            
            //检查是否所有分片都被覆盖
            for(Map.Entry<Integer, int[]> e : dbs.entrySet()) {
                shardings = e.getValue();
                for(int i = 0; i < IDBConst.MAX_SHARDING_NUM; i++) {
                    int j = i / Integer.SIZE;
                    int bitN = 1 << (i & (Integer.SIZE - 1));
                    if((shardings[j] & bitN) == 0) {
                        int dbNo = e.getKey();
                        if(dbNo < 0) {
                            dbNo += MAX_DB_NO;
                        }
                        throw new MeshException("invalid webdb config, empty sharding(" + i + ")#" + dbNo);
                    }
                }
            }
        }
        
        static DBCfg find(List<DBCfg> cfgs, String addr, int no, int shardStart, int shardEnd) {
            for(DBCfg cfg : cfgs) {
                if(cfg.addr.equals(addr) && cfg.no == no
                   && cfg.shardStart == shardStart && cfg.shardEnd == shardEnd) {
                    return cfg;
                }
            }
            return null;
        }
        
        static Map<String, StringBuilder> toJsons(List<DBCfg> cfgs) {
            Map<String, StringBuilder> dbs = new HashMap<>();
            for(DBCfg cfg : cfgs) {
                StringBuilder s = dbs.get(cfg.addr);
                if(s == null) {
                    s = new StringBuilder("["); //每个实例上可能承载多个数据库实例，用逗号分隔
                    dbs.put(cfg.addr, s);
                } else if(s.length() > 1) {
                    s.append(',');
                }
                s.append(cfg);
            }
            for(StringBuilder s : dbs.values()) {
                s.append(']');
            }
            return dbs;
        }
    }
}
