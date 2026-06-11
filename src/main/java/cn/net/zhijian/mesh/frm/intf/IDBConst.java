package cn.net.zhijian.mesh.frm.intf;

/**
 * 数据库常用常量
 * @author flyinmind of csdn.net
 *
 */
public interface IDBConst extends IConst {
    String SEARCHDB = "sdb";
    String TREEDB = "tdb";
    String RDB = "rdb";
    
    String DB_REQ_SERVICE = "service";
    String DB_REQ_DB = "db";
    String DB_REQ_DBNO = "no";
    String DB_REQ_ISWRITE = "write"; //是否有写操作
    //都是读操作时，可以指定任第一个（any）成功则返回
    String DB_REQ_ANY = "any";
    String DB_REQ_TIME = "time"; //请求时间，用于数据复制
    String DB_REQ_SHARDING = "sharding"; //请求分片
    String SEARCH_DOCS = "__docs__";
    String SEG_UPDATETIME = "update_time";

    String TREEDB_REQ_ACTIONS = "actions";
    String TREEDB_REQ_ACTION = "action";
    String TREEDB_REQ_KEY = "key";
    String TREEDB_REQ_VALUE = "val";
    String TREEDB_REQ_AS = "as";
    String TREEDB_REQ_DEFAULT = "default";
    String TREEDB_REQ_IGNORES = "ignores";

    String RDB_REQ_SQLS = "sqls";

    String ACTION_PUT = "put";
    String ACTION_PUTS = "puts"; //写入多个键值对
    String ACTION_PUT_IFABSENT = "putIfAbsent"; //如果不存在，才put
    String ACTION_PUTLIST = "putList";
    String ACTION_PUTMAP = "putMap"; //在原有的map基础上更新

    String ACTION_REMOVE = "rmv";
    String ACTION_REMOVE_FROMMAP = "rmvFromMap";
    String ACTION_REMOVE_FROMLIST = "rmvFromList";
    String ACTION_REMOVES = "rmvs";
    String ACTION_REMOVEDIR = "rmvDir";
    String ACTION_CREATEDIR = "crtDir";

    String ACTION_GET = "get";
    String ACTION_UPDATE = "update"; //用于search中，更新部分字段
    String ACTION_GETS = "gets";
    String ACTION_GETSUBS = "getSubs";
    String ACTION_GETSUBSANDITEMS = "getSubsAndItems";
    String ACTION_GET_FROMMAP = "getMap";
    String ACTION_GETS_MAP = "getsMap";
    String ACTION_GET_DIRID = "getId";
    String ACTION_LIST = "list";
    String ACTION_ITEMEXISTS = "itemExists";//返回OK或NOT_EXISTS
    String ACTION_DIREXISTS = "dirExists"; //返回OK或NOT_EXISTS
    String ACTION_NAMES = "names";
    String ACTION_SCRIPT = "script"; //通过复杂的逻辑生成一系列写操作，只在请求端解析

    String SQL_NAME = "name";
    String SQL_NEEDMODIFY = "needModify"; //是否需要修改sql，比如添加update_time
    String SQL_NEEDCOMPILE = "needCompile"; //db服务侧运行时是否需要进行替换变量
    String SQL_MERGE = "merge";
    String SQL_ISWRITE = "isWrite";
    String SQL_MULTI = "multi";
    //是否写结果到返回中，查询操作默认为true，写操作默认为false
    String SQL_TO_RESP = "toResp";
    String SQL_METAS = "metas";
    String SQL_WHEN = "when";
    String SQL_SQL = "sql";
    
    String SQL_EXPECTED = "expected";
    String SQL_EXPECTED_NUM = "num"; //期望的行数，-1表示大于0就可以，否则为具体的行数

    String SQL_IGNORES = "ignores";
    
    //metas的配置选项
    String META_NONE = "none";
    String META_EACH = "each";
    //multi为true且每行只有一列的情况下，可以用此选项优化
    String META_ONECOL = "oneCol";
    //第一列是key，如果只有2列，另一列为val，否则其他列合并为一个array作为val，没有列名称
    String META_KV = "kv";
    //第一列是key，如果2列，另一列为val，否则其他列合并为一个map作为val，包括列名称
    String META_KO = "ko";

    char SQL_QUOTE = '\'';
    
    String SEARCHDB_REQ_LIMIT = "limit"; //查询时，返回结果集的最大行数，0表示不限制
    String SEARCHDB_REQ_ACTION = "act"; //操作
    String SEARCHDB_REQ_TABLE = "table"; //表名称
    String SEARCHDB_REQ_TITLE = "title";
    String SEARCHDB_REQ_SUMMARY = "summary";
    String SEARCHDB_REQ_CONTENT = "content";
    String SEARCHDB_REQ_DID = "did";
    String SEARCHDB_REQ_SEARCH = "search";
    String SEARCHDB_RESP_DOCS = "docs";

    int MAX_SHARDING_NUM = 32768; //假设每个实例100万记录，则最高可达327亿
    int DEFAULT_MAX_CONN_NUM = 6; //默认最大数据库链接数
    int LOCAL_DBNO = 0; //webdb实例编号，0为默认编号
    
    String TREEDB_API_URL = "/treedb/request";
    String RDB_API_URL = "/rdb/request";
    String SEARCHDB_API_URL = "/search/request";
    
    String DB_MASTER = "master";
    String DB_SLAVE = "slave";

    //Sqlite、MySQL、PostGre的jdbc驱动不依赖其他库
    //SqlServer有太多依赖，在资源有限的环境中不建议使用
    enum DBType {SQLITE, MYSQL, POSTGRE, SQLSERVER, ORACLE, JDBC}
}