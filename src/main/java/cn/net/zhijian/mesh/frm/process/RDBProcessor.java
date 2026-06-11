package cn.net.zhijian.mesh.frm.process;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker.SqlType;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsDBProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsDBWorker;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.js.JsEngine;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 关系型数据库处理器
 * 是一个非常关键的类，因为在绝大部分系统中，关系型数据库操作都是核心
 * @author flyinmind of csdn.net
 *
 */
public class RDBProcessor extends AbsDBProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    protected static final String WEBDB_CFG_SQLS = "sqls";

    private SqlStatement[] sqls;

    public RDBProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    /**
     * 此处没有直接调用DBClient.rdbRequest，是因为rdbRequest不会做格式化替换。
     * 本函数在执行sql前，会将待执行的sql中的参数名替换成请求中实际的参数值。
     * sql中需要哪些参数，在加载配置时已经解析了，是否有写入操作，在解析时也已经确定。
     */
    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        return sendDBRequest(req, RDB_API_URL, this.db, respData);
    }
    
    /**
     * 有点扭曲的做法，将编译后的请求参数存入了HandleResult.data中
     * 如果发生错误，code不为OK
     * @param req 请求，用于解析占位符中的请求字段
     * @param db 数据库
     * @param respData 响应，用于解析占位符中的响应字段
     * @return 请求结果，处理错误码放在HandleResult.code中，
     * 请求内容放在HandleResult.data中
     */
    @Override
    protected HandleResult buildRequest(AbsServerRequest req, String db, Map<String, Object> respData, int dbNo) {
        Map<String, Object> reqData = new HashMap<>();
        reqData.put(DB_REQ_DB, db);
        reqData.put(DB_REQ_DBNO, dbNo);
        reqData.put(DB_REQ_ISWRITE, this.hasWrite);
        reqData.put(DB_REQ_TIME, req.reqTime);
        if(!this.hasWrite) {
            if(this.sqls.length > 1) {
                reqData.put(DB_REQ_ANY, this.any);
            }
        }

        //传递给RDB的sql列表，每个必须包括名称
        List<Map<String, Object>> oprs = new ArrayList<>();
        int no = 0;
        
        for(SqlStatement ssm : this.sqls) {
            no++;
            String whenFormula = null;
            //在请求前先判断是否需要运行，本地when不能使用@[]括起的webdb内部的返回结果
            if(ssm.whenType == SqlStatement.LOCAL_WHEN) { //在调用webdb前判断本地的when
                if(!needRun(req, respData, ssm.when)) {
                    LOG.debug("needn't run sql:{}@{}", ssm.name, no);
                    continue; //needn't run, just ignore it
                }
            } else if(ssm.whenType == SqlStatement.REMOTE_WHEN) {//然后发送webdb服务端when
                whenFormula = translateElements(ssm.when, req, respData);
                if(StringUtil.isEmpty(whenFormula)) { //空字符串也认为是false
                    continue;
                }
            }
            Map<String, Object> sql = new HashMap<>();
            //除js嵌入sql的情况，RDBProcessor中的所有sql都已经预处理过，
            //webdb中执行时无需增加update_time字段；
            //不设置SQL_NEEDMODIFY，在AbsRDBWorker(webdb中使用)默认为true
            //如果发到webdb中是脚本，则webdb会强制将SQL_NEEDMODIFY设置成true
            sql.put(SQL_NEEDMODIFY, false);
            sql.put(SQL_NAME, ssm.name);
            sql.put(SQL_TO_RESP, ssm.toResp);
            if(whenFormula != null) {
                sql.put(SQL_WHEN, whenFormula);
            }
            if(ssm.isWrite) {//有写操作时才会携带此返回码，指定影响行数为0时的返回码，不指定则返回OK
                if(ssm.expected != null) {
                    sql.put(SQL_EXPECTED, ssm.expected);
                }
            } else {
                sql.put(SQL_MULTI, ssm.multi);
                sql.put(SQL_METAS, ssm.metas);
                if(!ssm.multi) { //单行结果集，如果不是each，不能merge
                    if(ssm.merge && !ssm.metas.equals(META_EACH)) {
                        LOG.error("{} must be {} when {} is true", SQL_METAS, META_EACH, SQL_MERGE );
                        return new HandleResult(RetCode.WRONG_PARAMETER, SQL_METAS + " must be " + META_EACH);
                    }
                    sql.put(SQL_MERGE, ssm.merge);
                }
            }
            
            if(ssm.ignores != null) {
                sql.put(SQL_IGNORES, ssm.ignores);
            }
            /*
             * 转义sql字符串中的引号，
             * 如果是字符串，API配置中，必须自己在sql中打单引号
             */
            String sqlStr = translateElements(ssm.sqlElements, req, respData);
            if(sqlStr == null) {
                LOG.error("Invalid sql statement in {}.{}/{} when translating req", name(), no, ssm.name);
                return new HandleResult(RetCode.DB_ERROR, "fail to compile sql with req/resp");
            }
            
            sql.put(SQL_NEEDCOMPILE, ssm.needCompile);
            if(!ssm.needCompile) {//没有使用服务端参数的js/rs，在请求侧即可转成基本的sql
                SqlType type = AbsRDBWorker.getSqlType(sqlStr);
                if(type.laterModify()) { //运行时编辑
                    if(type == SqlType.SCRIPT) {//运行时编辑
                        sqlStr = JsEngine.getString(sqlStr.substring(JS_HEAD.length()));
                    } else if(type == SqlType.RUNTIMESCRIPT) {
                        sqlStr = sqlStr.substring(RS_HEAD.length());
                    }
                    sqlStr = StringUtil.trim(sqlStr); //脚本运行后的结果，可能只有换行空格等
                    if(StringUtil.isEmpty(sqlStr)) {
                        LOG.debug("ignore empty sql in {}.{}", name(), ssm.name);
                        continue; //脚本产生的sql，可能为空，可以不发到webdb中
                    }

                    HandleResult hr = HandleResult.tryParse(sqlStr);
                    if(hr != null) { //可能是sql错误，也可能是直接的结果，以{}包裹
                        if(hr.code != RetCode.OK) { //有时候会直接返回OK，不执行sql，但是并没有错误，比如hr服务中检查考勤异常时
                            LOG.error("Invalid sql statement in {}.{}/{} when tryParsing req", name(), no, ssm.name);
                            return hr;
                        }
                        respData.putAll(hr.data);
                        continue; //直接获得结果，不必再执行sql请求
                    }

                    //对js执行后的sql重新编辑
                    List<String> sqls = AbsRDBWorker.modifyDMLSqls(sqlStr, Long.toString(req.reqTime));
                    if(sqls == null || sqls.isEmpty()) {
                        LOG.error("Invalid sql statement in {}.{}/{} when compiling {}", name(), no, ssm.name, sqlStr);
                        return new HandleResult(RetCode.API_ERROR, "invalid script sql");
                    }

                    if(sqls.size() > 1) {
                        for(String s : sqls) { //多个sql，服务端将同名的sql影响的行数汇总
                            Map<String, Object> newSql = new HashMap<>(sql);
                            newSql.put(SQL_SQL, s);
                            oprs.add(newSql);
                        }
                        sql = null; //后面不用再往oprs中插入了
                    } else {
                        sqlStr = sqls.get(0);
                    }
                }
            }
            if(sql != null) {
                sql.put(SQL_SQL, sqlStr);
                oprs.add(sql);
            }
        }
        if(oprs.isEmpty()) {
            return new HandleResult(RetCode.NO_OPERATION, "empty");
        }
        reqData.put(RDB_REQ_SQLS, oprs);
        
        return new HandleResult(RetCode.OK, reqData);
    }
    
    /**
     * 解析数据库请求脚本，如果sqls为空则不解析，但是不会返回错误，
     * 因为有java逻辑的情况下可以没有sqls
     * @param url 接口URL
     * @param cfg 配置
     * @param request 请求
     * @param sqls 需执行的sql
     * @return 解析结果
     */
    protected boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request, List<Object> sqls) {
        if(!super.parse(url, cfg, request)) {
            return false;
        }
        
        if(sqls == null || sqls.isEmpty()) { //可以没有一个sql，只会提示
            LOG.info("There is no sqls under {}-{}", url.toString(), processName);
            return true;
        }

        List<SqlStatement> sqlStatements = new ArrayList<>();
        for(int i = 0; i < sqls.size(); i++) {
            List<SqlStatement> stmts = SqlStatement.parse(name(), sqls.get(i), availableParas(request));
            if(stmts == null || stmts.isEmpty()) {
                LOG.error("Fail to parse sql to SqlStatement under {}", url.toString());
                return false;
            }
            /*
             * 只要有一个sql是写入操作，则整个请求变成写入操作，启动事务，
             * 并且要对db对象做互斥造作。如果都是查询操作，则无需此操作。
             */
            for(SqlStatement ss : stmts) {
                if(ss.isWrite) {
                    this.hasWrite = true;
                }
                sqlStatements.add(ss);
            }
        }

        if(this.hasWrite) { //有写操作，不可以缓存
            if(useCache()) {
                LOG.error("Can't set cache if there're write-sqls in {}", url);
                return false;
            }
        } else { //没有写操作时，才可以读从库
            this.readSlave = ValParser.getAsBool(cfg, CFG_READSLAVE, false);
        }
        this.sqls = sqlStatements.toArray(new SqlStatement[0]);
        if(this.sqls.length == 0) {
            LOG.error("There is no sql under `{}.{}`", url, name());
            return false;
        }
        return true;
    }
    
    @Override
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        //逻辑没有直接写在此函数，因为DataExists中也会调用下面的函数
        //在DataExists中，sqls不是配置的，而是拼凑的
        return parse(url, cfg, request, ValParser.getAsList(cfg, WEBDB_CFG_SQLS));
    }

    private static class SqlStatement {
        private static final String DMLNow = "@{" + IConst.EMBEDED_REQUESTAT + '}';
        private static final char[][] WRITESQL_KWS = new char[][]{
            "update".toCharArray(), "insert".toCharArray(),
            "replace".toCharArray(), "delete".toCharArray()
        };
        public static final int NO_WHEN = 0;
        public static final int LOCAL_WHEN = 1;
        public static final int REMOTE_WHEN = 2;

        public final String name;
        /// 查询结果是否直接合并到map中，如果为false，则变成name->map{}
        public final boolean merge;
        /// 发生错误是，是否忽略错误，继续执行
        public final List<Integer> ignores;
        //结果集是否为多行
        public final boolean multi;
        public final boolean toResp; //结果集是否存到响应中，查询默认为true，写默认为false
        public final boolean isWrite; //是否为写sql
        //是否在结果集的每行中携带列名称，在webdb中使用，默认false，返回的是一个不带列名的list
        public final String metas;
        
        //sql，用占位符将sql分成多个子串，运行时需要替换占位符
        public final ScriptElement[] sqlElements;
        /*
         * 是否需要在服务端编译，
         * 如果所有的STR类型ScriptElement中都不存在@[...]则不需要
         * 大部分都不需要，在启动阶段就判断，减少webdb运行时替换的消耗。
         */
        public final boolean needCompile;
        public final Map<String, Object> expected; //增删改操作无被影响记录时的返回码
        //判断在什么情况下需要执行，返回一个bool值，如果false，则不执行
        //如果有@[!xxx]则发到webdb服务端判断
        public final ScriptElement[] when;
        public final int whenType;

        private SqlStatement(String name, boolean toResp, boolean needCompile,
                            boolean multi, boolean merge, List<Integer> ignores,
                            ScriptElement[] sql, boolean isWrite, String metas,
                            Map<String, Object> expected, ScriptElement[] when) {
            this.name = name;
            this.ignores = ignores;
            this.merge = merge;
            this.multi = multi;
            this.sqlElements = sql;
            this.isWrite = isWrite;
            this.metas = metas;
            this.needCompile = needCompile;
            this.expected = expected;
            this.when = when;
            this.toResp = toResp;
            int whenType = NO_WHEN;
            if(when != null && when.length > 0) {
                whenType = LOCAL_WHEN;
                for(ScriptElement se : when) {
                    if(se.getType() != ScriptElement.EleType.STR) {
                        continue;
                    }
                    String s = se.firstName();
                    int pos = s.indexOf(AbsDBWorker.PLACEHOLDER_START);
                    if(pos >= 0 && s.indexOf(AbsDBWorker.PLACEHOLDER_END, pos + AbsDBWorker.PLACEHOLDER_START.length()) > 0) {
                        whenType = REMOTE_WHEN;
                        break; //只要有一个中有webdb服务端占位符，则需要发送到webdb中处理
                    }
                }
            }
            this.whenType = whenType;
        }

        /**
         * 将sql配置解析为SqlStatement，
         * sqlitejdbc驱动支持用分号分开多个sql，但是安卓的sqlite不支持，
         * 所以这里将分号分开成多个SqlStatement
         * @param processName 进程名
         * @param o 被解析对象
         * @param availableParas 可以使用的字段
         * @return 解析结果
         */
        static List<SqlStatement> parse(String processName, Object o, Set<String> availableParas) {
            if(o == null) {
                LOG.error("Invalid sql statement {}, can't be null", processName);
                return null;
            }
            boolean merge = false;
            boolean multi = true;
            boolean isWrite = true;
            Map<String, Object> expected = null;
            List<Integer> ignores = null;
            String metas = META_EACH; //是否在响应结果集的每行中增加列名称
            String sSql;
            String name = null;
            ScriptElement[] when = null;
            
            Map<String, Object> cfg;
            if(o instanceof String) {
                sSql = StringUtil.trim((String)o);
                cfg = new HashMap<>();
            } else {
                if((cfg = ValParser.parseObject(o)) == null) {
                    LOG.error("Invalid sql statement config, need an object");
                    return null;
                }

                sSql = StringUtil.trim(ValParser.getAsStr(cfg, SQL_SQL, IConst.EMPTY_STR));
                name = ValParser.getAsStr(cfg, SQL_NAME, IConst.EMPTY_STR);
                if(name.length() > 30) {
                    LOG.error("Invalid sql statement name '{}', max length is 30", name);
                    return null;
                }
                ignores = RetCode.parseCodes(ValParser.getAsList(cfg, SQL_IGNORES));
                multi = ValParser.getAsBool(cfg, SQL_MULTI, true);
                metas = ValParser.getAsStr(cfg, SQL_METAS, META_EACH);
                if(!multi) { //单行结果集的情况下，才可能将结果合并到HandleResult.data的顶层中
                    if(metas.equalsIgnoreCase(META_EACH)) {
                        merge = ValParser.getAsBool(cfg, SQL_MERGE, true);
                    }
                }
                expected = ValParser.getAsObject(cfg, SQL_EXPECTED); //期望受影响的行数
                if(expected != null) {
                    Object c = expected.get(CFG_ERROR_CODE);
                    int code = RetCode.parseCode(c); //都转成整型错误码，webdb端不必再解析
                    expected.put(CFG_ERROR_CODE, code);
                }
                String s = ValParser.getAsStr(cfg, SQL_WHEN).trim();
                if(!StringUtil.isEmpty(s)) {
                    //是否运行此sql，则可以不指定，是一段逻辑运算，返回true或false
                    String formula = s.replace("&&", "*").replace("||", "+"); //变成数学算式
                    when = ScriptElement.parsePlaceHolder(formula, availableParas, IConst.EMPTY_STR, null);
                    if(when == null || when.length == 0) {
                        LOG.error("Fail to parse {} under {}", CFG_WHEN, processName);
                        return null;
                    }
                }
            }
            
            if(StringUtil.isEmpty(sSql)) {
                LOG.error("Invalid sql in {}", processName);
                return null;
            }

            boolean needCompile = AbsDBWorker.needCompile(sSql);
            SqlType sqlType = AbsRDBWorker.getSqlType(sSql);
            if(sqlType == SqlType.SELECT) {
                isWrite = false;
            } else if(sqlType.laterModify()) { //到运行时才能确定正确的类型
                if(cfg.containsKey(SQL_ISWRITE)) {
                    isWrite = ValParser.getAsBool(cfg, SQL_ISWRITE, true);
                } else {
                    //此处的判断并不完全准确，当不同分支返回的sql性质不同时，判断可能会出错，但是只会严判
                    isWrite = AbsRDBWorker.findSqlKeyWordsInScript(sSql, WRITESQL_KWS) > 0;
                }
            }
            boolean toResp = ValParser.getAsBool(cfg, SQL_TO_RESP, !isWrite);
            
            if(StringUtil.isEmpty(name)) {
                name = processName;
                if(!isWrite && !merge) {
                    LOG.warn("No name for the sql,use process-name '{}' as default:`{}`", name, sSql);
                }
            }

            List<String> modifiedSqls;
            boolean needModify = ValParser.getAsBool(cfg, SQL_NEEDMODIFY, true);
            if(needModify) {
                modifiedSqls= AbsRDBWorker.modifyDMLSqls(sSql, DMLNow); //script类型只能在运行后编辑
                if(modifiedSqls == null || modifiedSqls.isEmpty()) {
                    LOG.error("Fail to modify sql {}", sSql);
                    return null;
                }
            } else {
                modifiedSqls = Arrays.asList(sSql);
            }

            List<SqlStatement> statements = new ArrayList<>();
            for(String sql : modifiedSqls) {
                ScriptElement[] sqlElements = ScriptElement.parsePlaceHolder(sql, availableParas, "'", "''");
                if (sqlElements == null || sqlElements.length == 0) {
                    LOG.error("Fail to parse sql placeholders in `{}`", sql);
                    return null;
                }
                statements.add(new SqlStatement(name, toResp, needCompile, multi, merge,
                        ignores, sqlElements, isWrite, metas, expected, when));
            }
            return statements;
        }
    }
}
