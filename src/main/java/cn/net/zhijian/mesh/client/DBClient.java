package cn.net.zhijian.mesh.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.bean.ShardingNode;
import cn.net.zhijian.mesh.bean.WebdbDNS;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker.SqlType;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo.ServiceType;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 数据库处理客户端
 * 1）请求webdb（包括rdb、treedb与searchdb）时使用oauth服务，
 *    clientId为调用方，scope为'db:' + 数据库名称；
 * 2）treedb服务端底层仍然是rdb，只是接口不同；
 * 
 * @author flyinmind of csdn.net
 *
 */
public final class DBClient extends ServiceClient implements IDBConst, IOAuth, IThreadPool {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String BIOS_API_DBNODES = "/db/nodes";

    /**
     *   "service":"serviceName",
     *   "db":"dbName",
     *   "act":"put/get/remove/create/batchWrite...",
     *   "key":"key name",
     *   "val":"value, it can be a string or a map in put...",
     *   "acts":[{
     *       "act":"...",
     *       "key":"...",
     *      "val":"..."
     *   },...]
     * @param req 请求
     * @param actions 操作
     * @return 请求结果
     */
    public static CompletableFuture<HandleResult> treedbRequest(DBReqBuilder req, TreeAction[] actions) {
        req.url(TREEDB_API_URL);
        if(LOG.isDebugEnabled()) {
            LOG.debug("treedbRequest({})", req);
        }
        return dbToken(req).thenComposeAsync((token) -> {
            int num = actions.length;
            boolean hasWrite = false;

            @SuppressWarnings("unchecked")
            Map<String, Object>[] acts = new Map[num];
            for(int i = 0; i < num; i++) {
                acts[i] = actions[i].get();
                if(actions[i].isWrite) {
                    hasWrite = true;
                }
            }
            
            req.put(TREEDB_REQ_ACTIONS, acts);
            if(hasWrite) {
                req.readSlave(false);
            }

            return rdbPost(req);
        }, Pool);
    }

    public static CompletableFuture<HandleResult> searchDBRequest(DBReqBuilder req, DocAction action) {
        req.url(SEARCHDB_API_URL);
        if(LOG.isDebugEnabled()) {
            LOG.debug("searchdbRequest({})", req);
        }
        return dbToken(req).thenComposeAsync((token) -> {
            req.putAll(action.toMap());
            if(action.isSearch()) {
                return searchDBPost(req);
            }
            req.readSlave(false);
            return rdbPost(req);
        }, Pool);
    }
    
    /**
     * {
     *   "service":"serviceName",
     *   "db":"dbName",
     *   "write":"Are there any write SQLs",
     *   "sqls":[{
     *       "name":"result name of the SQL statement",
     *       "needModify":"add update_time segment",
     *       "merge":"put the result directly into the 'data' map",
     *       "multi":"multiple lines",
     *       "metas":"with column name when return query result,each/none/specific name",
     *       "sql":"CRUD SQL"
     *   }...]
     * }
     * @param req 调用builder
     * @param actions 数据库操作
     * @return 数据库请求结果
     */
    public static CompletableFuture<HandleResult> rdbRequest(DBReqBuilder req, SQLAction[] actions) {
        req.url(RDB_API_URL);
        if(LOG.isDebugEnabled()) {
            LOG.debug("rdbRequest({})", req);
        }
        return dbToken(req).thenComposeAsync((token) -> {
            boolean hasWrite = false;

            req.token(token);
            List<Map<String, Object>> sqls = new ArrayList<>(actions.length);
            for(SQLAction sa : actions) {
                sqls.add(sa.get());
                if(sa.isWrite()) {
                    hasWrite = true;
                }
            }
            req.put(RDB_REQ_SQLS, sqls).put(DB_REQ_ISWRITE, hasWrite);

            if(hasWrite) {
                req.readSlave(false);
            }
            return rdbPost(req);
        }, Pool);
    }

    public static CompletableFuture<String> dbToken(DBReqBuilder req) {
        return serviceToken(req, TOKENTYPE_DB, DB_CALLEE_HEAD + req.db);
    }
    
    /**
     * 调用时，根据caller确定被调用方的IP
     * @param req 请求builder
     * @return 数据库节点查询结果
     */
    private static CompletableFuture<NodeAddress> dbLookup(DBReqBuilder req) {
        return lookupWebDB(req).thenComposeAsync(webdbDns -> {
            if(webdbDns == null) {
                LOG.error("Invalid webdb dns config for {}.{}, it's null", req.caller.name, req.db);
                return CompletableFuture.completedFuture(null);
            }
            int cid = req.cid();
            NodeAddress node = webdbDns.lookup(req.shardId, req.readSlave, true, cid);
            return CompletableFuture.completedFuture(node);
        }, Pool);
    }

    /**
     * 调用时，根据caller或随机方式，确定被调用方的IP
     * @param req 请求builder
     * @return 数据库节点查询结果
     */
    private static CompletableFuture<WebdbDNS> lookupWebDB(DBReqBuilder req) {
        String name = PartitionConfig.instance().partition + " " + req.dbNo;
        WebdbDNS webdbDns = (WebdbDNS)LocalDNS.get(name);
        if (webdbDns != null && !webdbDns.needUpdate()) {
            /*
             * 只要不为空，则使用它，哪怕超期一点点，继续等待其他线程继续更新。
             * 如果一次更新失败，则隔一段时间后再次尝试更新，
             * 此方法能解决平时的浪涌问题，但是解决不了大面积宕机后重启时的浪涌问题。
             */
            return CompletableFuture.completedFuture(webdbDns);
        }
        //过期后，刷新一次，默认每隔300秒刷新
        return getDbNodes(req).thenComposeAsync(dbDns -> {
            if (dbDns == null) {
                if (webdbDns != null) {
                    webdbDns.resetUpdate(); //本次失败，继续使用历史记录，下一个请求需要再次请求
                }
                LOG.error("Fail to get db nodes from bios of {}.{}", req.caller.name, req.db);
                return CompletableFuture.completedFuture(webdbDns);
            }
            setLocalDns(name, dbDns);
            return CompletableFuture.completedFuture(dbDns);
        }, Pool);
    }

    /**
     * 调用/bios/db/nodes，获取一个db的所有主节点、从节点信息。
     * @param dbReq 请求体
     * @return 数据库dns
     */
    public static CompletableFuture<WebdbDNS> getDbNodes(DBReqBuilder dbReq) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("dbLookup.getDbNodes(caller:{},cid:{},db:{},dbNo:{})",
                    dbReq.caller.name, dbReq.cid, dbReq.db, dbReq.dbNo);
        }
        ServiceReqBuilder req = new ServiceReqBuilder(dbReq.caller, SERVICE_BIOS)
                .url(BIOS_API_DBNODES)
                .appendPara("dbNo", Integer.toString(dbReq.dbNo), false)
                .traceId(dbReq.traceId)
                .appToken("*")
                .cid(dbReq.cid);
        return BiosClient.get(req).thenComposeAsync((hr)-> {
            if(hr.code != RetCode.OK || hr.data == null || hr.data.isEmpty()) {
                LOG.error("Fail to call {} in getDbNodes({}),result:{}", BIOS_API_DBNODES, dbReq.db, hr.brief());
                return CompletableFuture.completedFuture(null);
            }

            WebdbDNS dns = WebdbDNS.parse(dbReq.caller.name, dbReq.db, hr.data);
            return CompletableFuture.completedFuture(dns);
        }, Pool);
    }

    /**
     * 异步post调用，由caller、db、shardId共同决定被调数据库实例的IP，
     * shardId指定了数据归属的分片号（0-32767），分库的情况，不同分片可能在不同的实例上，
     * 如果不分库，则全部在一个实例上，业务数据表中可以记录此分片号，通过@{#shard}得到此参数，
     * 这样在转移分片的分布时，会比较方便，否则需要将数据逐条读取出来，然后根据分片字段再计算分片号。
     * @param req 请求builder
     * @return 数据库查询结果
     */
    public static CompletableFuture<HandleResult> rdbPost(DBReqBuilder req) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("rdbPost({})", req.toString());
        }

        return dbLookup(req).thenComposeAsync((node)-> {
            if(node == null) {
                LOG.error("Node of {}.{} not found, shard:{}", req.caller.name, req.db, req.shardId);
                return HandleResult.future(RetCode.SERVICE_NOT_FOUND, "db `" + req.caller.name + '.' + req.db + "` not found");
            }

            return servicePost(node, req);
        }, Pool);
    }
    
    /**
     * 只用于search的get方法，所有实例都查询一遍，然后汇总在各个实例上的查询结果
     * @param req 查询构造器
     * @return 全文查询结果
     */
    public static CompletableFuture<HandleResult> searchDBPost(DBReqBuilder req) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("searchDBPost(url:{},{})", SEARCHDB_API_URL, req.toString());
        }

        return lookupWebDB(req).thenComposeAsync((webdbdns)-> {
            if(webdbdns == null) {
                return HandleResult.future(RetCode.SERVICE_NOT_FOUND, "db `" + req.caller.name + '.' + req.db + "` not found");
            }

            @SuppressWarnings("unchecked")
            CompletableFuture<HandleResult>[] futures = new CompletableFuture[webdbdns.shardings.length];
            int i = 0;
            int cid = req.cid();
            req.url(SEARCHDB_API_URL);
            for(ShardingNode node : webdbdns.shardings) {
                NodeAddress n = node.lookup(false, true, cid);
                if(n == null) {
                    LOG.error("Can't find a valid node in {}", node);
                    continue;
                }
                futures[i++] = servicePost(n, req);
            }

            //allOf在所有查询结束后运行
            return CompletableFuture.allOf(futures).thenComposeAsync((v) -> {
                List<Object> docs = new ArrayList<>();
                for(CompletableFuture<HandleResult> f : futures) {
                    try {
                        HandleResult hr = f.get();
                        if(hr.code == RetCode.OK && hr.data != null) {
                            List<Object> docsInResp = ValParser.getAsList(hr.data, SEARCHDB_RESP_DOCS);
                            if(docsInResp != null) {
                                docs.addAll(docsInResp);
                            }
                        }
                    } catch (InterruptedException | ExecutionException e1) {
                        return HandleResult.future(HandleResult.InternalError);
                    }
                }
                Map<String, Object> resp = MapBuilder.of(SEARCHDB_RESP_DOCS, docs);
                return HandleResult.future(resp);
            }, Pool);
        }, Pool);
    }

    /**
     * 树状数据库操作类
     * @author flyinmind of csdn.net
     *
     */
    public static class TreeAction {
        private final Map<String, Object> map = new HashMap<>();
        private boolean isWrite = false;

        public static TreeAction put(String k, String v) {
            return new TreeAction().action(ACTION_PUT).key(k).value(v);
        }

        public static TreeAction get(String k) {
            return new TreeAction().action(ACTION_GET).key(k);
        }

        public static TreeAction itemExists(String k) {
            return new TreeAction().action(ACTION_ITEMEXISTS).key(k);
        }

        public static TreeAction dirExists(String k) {
            return new TreeAction().action(ACTION_DIREXISTS).key(k);
        }

        public static TreeAction gets(String k) {
            return new TreeAction().action(ACTION_GETS).key(k);
        }

        public static TreeAction createDir(String dir) {
            return new TreeAction().action(ACTION_CREATEDIR).key(dir);
        }

        public TreeAction action(String v) {
            //put...|remove...|create...
            isWrite = AbsDBWorker.isWriteTreeDBAct(v);
            map.put(TREEDB_REQ_ACTION, v);
            return this;
        }

        public TreeAction key(String v) {
            map.put(TREEDB_REQ_KEY, v);
            return this;
        }

        public TreeAction value(String v) {
            map.put(TREEDB_REQ_VALUE, v);
            return this;
        }

        public TreeAction as(String v) {
            map.put(TREEDB_REQ_AS, v);
            return this;
        }

        public Map<String, Object> get() {
            return map;
        }

        public boolean isWrite() {
            return isWrite;
        }
    }
    
    /**
     * 关系型数据库操作
     * @author flyinmind of csdn.net
     *
     */
    public static class SQLAction {
        private static final Logger LOG = LogUtil.getInstance();

        private Map<String, Object> map = new HashMap<>();
        private boolean isWrite = false;

        public static SQLAction create(String name) {
            SQLAction act = new SQLAction();
            act.map.put(SQL_NAME, name);
            return act;
        }

        public static SQLAction parse(Map<String, Object> sql) {
            SQLAction act = new SQLAction();
            String s = ValParser.getAsStr(sql, SQL_SQL);
            if(StringUtil.isEmpty(s)) {
                LOG.error("Fail to parse sql, no '{}' segment", SQL_SQL);
                return null;
            }
            act.map = sql;
            act.isWrite = AbsRDBWorker.getSqlType(s) != SqlType.SELECT;
            return act;
        }

        public static SQLAction[] parse(List<Object> sqls) {
            SQLAction[] acts = new SQLAction[sqls.size()];
            int i = 0;
            for(Object o : sqls) {
                Map<String, Object> map = ValParser.parseObject(o);
                SQLAction act = parse(map);
                if(act == null) {
                    LOG.error("Fail to parse sql@{},cfg:{}", i, map);
                    return null;
                }
                acts[i++] = act;
            }
            return acts;
        }

        public SQLAction sql(String v) {
            map.put(SQL_SQL, v);
            this.isWrite = AbsRDBWorker.getSqlType(v) != SqlType.SELECT;
            return this;
        }

        public SQLAction needModify(boolean v) {
            map.put(SQL_NEEDMODIFY, v);
            return this;
        }

        public SQLAction merge(boolean v) {
            map.put(SQL_MERGE, v);
            return this;
        }

        public SQLAction multi(boolean v) {
            map.put(SQL_MULTI, v);
            return this;
        }

        /**
         * 数据库select返回列名的处理
         * @param v none|each|oneCol or meta_name
         * @return 对象本身
         */
        public SQLAction metas(String v) {
            map.put(SQL_METAS, v);
            return this;
        }
        
        public SQLAction ignores(int[] codes) {
            map.put(SQL_IGNORES, codes);
            return this;
        }

        public Map<String, Object> get() {
            return map;
        }

        public boolean isWrite() {
            return isWrite;
        }
    }
    
    /**
     * 搜索数据库操作
     * @author flyinmind of csdn.net
     *
     */
    public static class DocAction {
        private final Map<String, Object> map = new HashMap<>();
        private boolean isSearch = false;

        public static DocAction put(String did, String title, String summary, String content) {
            return new DocAction().action(ACTION_PUT)
                    .did(did).title(title).summary(summary).content(content);
        }

        public static DocAction update(String did, String title, String summary, String content) {
            return new DocAction().action(ACTION_UPDATE)
                    .did(did).title(title).summary(summary).content(content);
        }

        /**
         * 创建一个search action
         * @param args 逗号分隔
         * @return 全文索引操作
         */
        public static DocAction search(String args) {
            return new DocAction().action(ACTION_GET).args(args);
        }

        public static DocAction remove(String did) {
            return new DocAction().action(ACTION_REMOVE).did(did);
        }

        public DocAction action(String v) {
            isSearch = v.equalsIgnoreCase(ACTION_GET);
            map.put(SEARCHDB_REQ_ACTION, v);
            return this;
        }

        public DocAction table(String v) {
            if(!StringUtil.isEmpty(v)) {
                map.put(SEARCHDB_REQ_TABLE, v);
            }
            return this;
        }
        
        public DocAction title(String v) {
            if(!StringUtil.isEmpty(v)) {
                map.put(SEARCHDB_REQ_TITLE, v);
            }
            return this;
        }

        public DocAction summary(String v) {
            if(!StringUtil.isEmpty(v)) {
                map.put(SEARCHDB_REQ_SUMMARY, v);
            }
            return this;
        }

        public DocAction content(String v) {
            if(!StringUtil.isEmpty(v)) {
                map.put(SEARCHDB_REQ_CONTENT, v);
            }
            return this;
        }

        /**
         * 设置数据id
         * @param v 数据id
         * @return 对象本身
         */
        public DocAction did(String v) {
            map.put(SEARCHDB_REQ_DID, v);
            return this;
        }

         public DocAction args(String args) {
            map.put(SEARCHDB_REQ_SEARCH, args);
            return this;
        }

        public Map<String, Object> toMap() {
            return map;
        }

        public boolean isSearch() {
            return isSearch;
        }
    }
    
    /**
     * 数据库请求构造器
     * 继承自ServiceReqBuilder，懒得做模板类了，
     * 使用时，如果出现返回类型不一致，改成单独调用函数即可。
     * @author flyinmind of csdn.net
     *
     */
    public static class DBReqBuilder extends ServiceReqBuilder {
        public final String db; //数据库
        public final int dbNo; //webdb实例编号
        private int shardId = -1; //分片号，默认为0
        private boolean readSlave; //是否读从库，如果有写操作，此项强制为false

        public DBReqBuilder(ServiceInfo caller, String db, int dbNo) {
            super(caller, SERVICE_WEBDB);
            this.db = db;
            this.dbNo = dbNo;
            put(DB_REQ_DBNO, dbNo);
        }

        /**
         * 设置sharding分片号
         * 如果不支持sharding的数据库操作，shardId即使设置了，也不会产生作用
         * @param shardId 分片号
         * @return builder
         */
        public DBReqBuilder shardId(int shardId) {
            this.shardId = shardId;
            return this;
        }

        public DBReqBuilder readSlave(boolean readSlave) {
            this.readSlave = readSlave;
            return this;
        }
        
        public int shardId() {
            return shardId;
        }
        
        @Override
        public DBReqBuilder body(String body) {
            throw new UnsupportedOperationException("set body not allowed");
        }
        
        @Override
        void adjustData() {
            data.put(DB_REQ_DB, this.db);
            //如果传递了分片，在webdb中处理时，会判断是否在它能够处理的范围内
            //如果不在实例的范围内，则返回INVALID_NODE（113）
            if(shardId >= 0) {
                data.put(DB_REQ_SHARDING, shardId);
            }
        }
        
        @Override
        public DBReqBuilder cid(int cid) {
            //如果发起调用的服务是公司级服务，则使用公司id，其他的都用首要公司ID
            //首要公司信息从配置中加载，第一个即为首要公司，在根环境，只有一个公司信息，id固定为1
            if(caller.type == ServiceType.COMPANY) {
                this.cid = cid;
            } else { //非公司服务，数据库都使用首要公司的，比如config，共用的，且本身数据是区分公司的
                CompanyInfo ci = CompanyInfo.instance();
                this.cid = ci.id;
            }
            this.headers.put(IConst.HEAD_CID, Integer.toString(this.cid));
            return this;
        }

        @Override
        public String toString() {
            return super.toString() + ",caller:" + caller.name
                    + ",db:" + this.db
                    + ",dbNo:" + this.dbNo
                    + ",shardId:" + shardId
                    + ",traceId:" + traceId
                    + ",url:" + url;
        }
    }
}
