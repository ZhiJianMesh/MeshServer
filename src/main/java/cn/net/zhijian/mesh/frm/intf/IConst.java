package cn.net.zhijian.mesh.frm.intf;

import cn.net.zhijian.util.IUtil;

/**
 * 公用常量
 * @author flyinmind of csdn.net
 *
 */
public interface IConst extends IUtil {
    String ENGINEVERSION = "0.11.1";
    String BUILDVERSION = "0.10.0";
    String SERVICES_ROOT = "services"; //服务根路径

    String SERVICE_WEBDB = "webdb";
    String SERVICE_OAUTH2 = "oauth2";
    String SERVICE_HTTPDNS = "httpdns"; // dns 解析
    String SERVICE_KEYSTORE = "keystore"; // 密码库
    String SERVICE_SCHEDULE = "schedule"; // 定时任务
    String SERVICE_USER = "user";
    String SERVICE_UNIUSER = "uniuser";
    String SERVICE_CONFIG = "config";
    String SERVICE_COMPANY = "company"; // 负责与公司相关的能力，比如注册，云侧服务验证
    String SERVICE_APPSTORE = "appstore"; // 负责服务版本发布、安装、升级等
    String SERVICE_ASSETS = "assets";
    String SERVICE_SEQID = "seqid";
    String SERVICE_BIOS = "bios"; //记录服务信息、节点状态、底层密码
    String SERVICE_OM = "om";
    String SERVICE_GATEWAY = "gateway";
    String SERVICE_ADDRESS = "address";
    String SERVICE_WORKFLOW = "workflow"; //工作流服务
    String SERVICE_WWW = "www"; //首页
    String SERVICE_BACKEND = "backend"; //实例后台服务

    String SERVICE_URL_ROOT = "root";
    String SERVICE_URL_API = "api";
    String SERVICE_FILE_DIR = "file";
    String SERVICE_CLIENT_ZIP = "client.zip";
    String SERVICE_SERVER_ZIP = "server.zip";
    String SERVICE_CONFIG_FILE = "service.cfg";
    String DATABASE_CONFIG_FILE = "database.cfg";
    String LOCAL_DATABASE_CONFIG_FILE = "database.loc.cfg";

    String FAVICON_FILE = "favicon.png";
    String INDEX_FILE = "/index.html";
    String URL_DYNAMIC_FLAG = "/-/";

    String CFG_ERROR_CODE = "errorCode"; //发生错误时的返回码
    String CFG_ERROR_INFO = "errorInfo";

    String SERVICE_SEPERATOR_S = "-";
    char SERVICE_SEPERATOR_C = '-';
    
    String API_CLIENT_INFO = "client_info"; //端侧信息
    String API_CHECKUP = "checkup"; //健康检查
    String API_SERVICE_APILIST = "apis"; //服务api列表
    String API_SERVICE_FILELIST = "files"; //服务api列表
    String API_SERVICE_LIST = "services"; //服务列表
    String API_SERVICE_INITDB = "initdb"; //初始化数据库，完成建表，只用在公有云
    String API_SERVICE_RMVDB = "removedb"; //删除公司的数据库，只用在公有云
    String SYS_CONF_DIR = "conf";

    //请求oauth时，scope的头部内容，区分不同的认证方式
    String DB_CALLEE_HEAD = "db:";
    String SERVICE_CALLEE_HEAD = "sv:";
    String USER_CALLEE_HEAD = "usr:";
    String JS_HEAD = "js:"; //配置中需要按js处理的标识
    String RS_HEAD = "rs:"; //运行时拼接的脚本，运行时再编辑，与js类似，但是比js高效
    
    String HEAD_TRACE_ID = "trace_id";
    String HEAD_CID = "cid"; //每个请求都必须携带cid头
    String HEAD_ACCESS_TOKEN = "access_token";
    
    String EMBEDED_REQUESTAT = "#reqAt";
    String EMBEDED_SERVICE = "#service"; //当前服务

    String EMBEDED_TOKEN_CALLER = "#tokenCaller";
    String EMBEDED_TOKEN_CALLEE = "#tokenCallee";
    String EMBEDED_TOKEN_PARTITION = "#tokenPartId";
    String EMBEDED_TOKEN_EXT = "#tokenExt";
    String EMBEDED_TOKEN_ACC = "#tokenAcc";
    String EMBEDED_TOKEN_CID = "#tokenCid";
    String EMBEDED_TOKEN_FEATURE = "#feature";
    
    String EMBEDED_DB_SHARD = "#shard"; //数据分片
    String EMBEDED_CODE = "#code"; //如果发生错误码转换，此字段返回实际的错误码
    String EMBEDED_INFO = "#info"; //上一步的info，code没有必要，因为code不为OK，则结束
    String EMBEDED_ERROR_HANDLE = "#errorHandle"; //发生错误时，记录错误发生的处理名称

    char BEGIN_CHAR_HEADER = '^';
    char BEGIN_CHAR_RESPONSE = '!';
    char QUOTATION_MARK = '`'; //正常的引号使用`，但是兼容单引号

    String CER_DOMAIN = ".zhijian.net.cn";
    int PORT = 8523;
    String API_DOMAIN = "api" + CER_DOMAIN;
    String SCHEME = "https://"; //服务之间，以及端侧与服务器之间，默认使用https
    
    /* 公司对内ID，用于一些本地服务中，对外的是CompanyInfo中设置的
     * 类似环回地址127.0.0.1与本机对外地址的区别，在内部用处是一样的，但是127.0.0.1不能对外使用
     */
    int NULL_COMPANY_ID = -1;
    int LOCAL_COMPANY_ID = 0;
    int ROOT_COMPANY_ID = 1; //云上虚拟公司id
    int MIN_COMPANY_ID = 1024; //1024以内为保留的公司ID
    
    
    //webdb config segments
    // 数据库编号，一个编号对应一组数据库服务器实例，多个实例时，默认第一个是主服务器
    // 每个公司都分配一个数据库编号，多个公司可以分配到同一个数据库实例上
    String CFG_SEG_NO = "no";
    String CFG_SEG_TYPE = "type"; //数据库类型，当前只有sqlite
    String CFG_SEG_LEVEL = "level"; //切换顺序，level越高越次要
    String CFG_SEG_SHARDSTART = "shardStart"; //分片开始（包括）
    String CFG_SEG_SHARDEND = "shardEnd"; //分片结束（不包括）
    String CFG_SEG_MASTER = "master"; //主实例，最多只能有一个
    String CFG_SEG_DBURL = "url"; //数据库URL，sqlite无此配置
    //slave或master模式，默认为master
    String CFG_SEG_MODE = "mode";
    String CFG_SEG_SLAVES = "slaves"; //从实例列表
    String CFG_SEG_WRITECONN = "writeConn"; //写连接个数
    String CFG_SEG_READCONN = "readConn"; //读连接个数，sqlite读写是分开的
    
    long STARTUP_AT = System.currentTimeMillis();
}