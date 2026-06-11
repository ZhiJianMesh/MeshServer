package cn.net.zhijian.mesh.frm.process;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.DBClient;
import cn.net.zhijian.mesh.client.DBClient.DBReqBuilder;
import cn.net.zhijian.mesh.client.DBClient.DocAction;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsDBProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 全文搜索数据操作，每个doc包括docid、title、summary、content四个字段，
 * 包括get、put、rmv三个操作。
 * get时，search参数形如“a*,bb,cc”，可以包括多个，以逗号分隔。
 * put操作的配置，其中docid必须由参数输入，title、summary、content可以用参数输入，
 * 也可以通过配置，将多个参数组合起来，如同RDBProcessor中的sql一样
 * @author flyinmind of csdn.net
 *
 */
public class SearchDBProcessor extends AbsDBProcessor {
    private static final Logger LOG = LogUtil.getInstance();

    private static final String CFG_TABLE = "table";
    private static final String CFG_ACTION = "action";
    private static final String CFG_DID = "did";
    private static final String CFG_TITLE = "title";
    private static final String CFG_SUMMARY = "summary";
    private static final String CFG_CONTENT = "content";
    
    protected String action;
    protected ScriptElement[] table = null;

    protected ScriptElement[] did = null;
    protected ScriptElement[] title = null;
    protected ScriptElement[] summary = null;
    protected ScriptElement[] content = null;
    protected ScriptElement[] limit = null; //get时，最大返回函数，0表示不限制

    public SearchDBProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        if(this.action.equals(ACTION_GET)) { //从每个分库中查询，然后汇总，所以不关心分片
            String search = translateElements(this.content, req, respData); //待搜索的字符串
            String limit = translateElements(this.limit, req, respData); //解释之后必须是一个大于0的数字
            String table = translateElements(this.table, req, respData);
            return req.getDbNo().thenComposeAsync(dbNo -> {
                if(dbNo < 0) {
                    return futureResult(RetCode.NO_RIGHT, "db not open");
                }
                DBReqBuilder builder = new DBReqBuilder(serviceInfo(), this.db, dbNo)
                        .readSlave(this.readSlave);
                builder.cid(req.cid())
                    .traceId(req.traceId)
                    .url(SEARCHDB_API_URL)
                    .putAll(DocAction.search(search).table(table).toMap())
                    .put(DB_REQ_TIME, req.reqTime)
                    .put(SEARCHDB_REQ_LIMIT, limit);
                //所有分库都查一遍，然后合并
                return DBClient.dbToken(builder).thenComposeAsync((token) -> {
                    if(StringUtil.isEmpty(token)) {
                        LOG.error("Fail to get token of caller {}", this.serviceInfo().name);
                        return futureResult(RetCode.NO_RIGHT);
                    }
                    builder.token(token);
                    return DBClient.searchDBPost(builder);
                }, Pool);
            }, Pool);
        }
        
        return sendDBRequest(req, SEARCHDB_API_URL, this.db, respData);
    }
    
    @Override
    protected HandleResult buildRequest(AbsServerRequest req, String db, Map<String, Object> respData, int dbNo) {
        // 对应关系型数据库某行的唯一主键
        String did = translateElements(this.did, req, respData);
        if(StringUtil.isEmpty(did)) {
            return new HandleResult(RetCode.INTERNAL_ERROR, "no did in write-operations");
        }
        
        Map<String, Object> reqData = new HashMap<>();
        String table = translateElements(this.table, req, respData); 

        reqData.put(DB_REQ_DB, db);
        reqData.put(DB_REQ_DBNO, dbNo);
        if(this.action.equals(ACTION_REMOVE)) {
            reqData.putAll(DocAction.remove(did).table(table).toMap());
            return new HandleResult(RetCode.OK, reqData);
        }
        
        String title = translateElements(this.title, req, respData);
        String summary = translateElements(this.summary, req, respData);
        String content = translateElements(this.content, req, respData);

        if(this.action.equals(ACTION_PUT)) {
            reqData.putAll(DocAction.put(did, title, summary, content).table(table).toMap());
        } else {
            reqData.putAll(DocAction.update(did, title, summary, content).table(table).toMap());
        }
        return new HandleResult(RetCode.OK, reqData);        
    }
    
    @Override
    protected int calculateShard(AbsServerRequest req, Map<String, Object> resp) {
        String sDid = translateElements(this.did, req, resp);
        long lShard = StringUtil.longHashCode(sDid); //对应关系型数据库某行的主键，通常是一个long型整数
        return (int)(ValParser.absLong(lShard) % MAX_SHARDING_NUM); //只用docid做数据分片
    }
    
    /**
     * 解析请求的行为，配置形式为：
     * db:"searchdb",
     * action:"put",
     * title:"背影", passed by a parameter
     * summary:"朱自清的散文，描写儿子与父亲之间的微妙情感", or passed by a parameter
     * content:"xxxx", or passed by a parameter
     *
     * @param url url
     * @param cfg 配置内容
     * @param request 请求配置
     * @return 解析成功返回true
     */
    @Override
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        if(!super.parse(url, cfg, request)) {
            return false;
        }
        
        Set<String> availableParas = availableParas(request);
        this.table = parseTable(availableParas, cfg);
        if(this.table == null || this.table.length == 0) {
            LOG.error("Invalid parameter {},under {}.{}", CFG_TABLE, url, name());
            return false;
        }
        
        this.action = ValParser.getAsStr(cfg, CFG_ACTION);
        if(ACTION_REMOVE.equalsIgnoreCase(this.action)) {
            this.did = parseScript(availableParas, cfg, CFG_DID);
            if(this.did == null || this.did.length == 0) {
                LOG.error("Invalid parameter {},under {}.{}", CFG_DID, url.toString(), name());
                return false;
            }
        } else if(ACTION_PUT.equalsIgnoreCase(this.action) || ACTION_UPDATE.equalsIgnoreCase(this.action)) {
            this.did = parseScript(availableParas, cfg, CFG_DID);
            if(this.did == null || this.did.length == 0) {
                LOG.error("Invalid parameter {},under {}.{}", CFG_DID, url.toString(), name());
                return false;
            }

            this.title = parseScript(availableParas, cfg, CFG_TITLE);
            this.summary = parseScript(availableParas, cfg, CFG_SUMMARY);
            this.content = parseScript(availableParas, cfg, CFG_CONTENT);
            if(this.title == null && this.summary == null && this.content == null) {
                LOG.error("title,summary,content cann't all be null under {}.{}", url.toString(), name());
                return false;
            }
        } else if(this.action.matches("^" + ACTION_GET + "( @\\{.+\\})*$")){
            this.content = parseScript(availableParas, cfg, CFG_CONTENT);
            if(this.content == null || this.content.length == 0) {
                LOG.error("Fail to parse {},under {}.{}", CFG_CONTENT, url.toString(), name());
                return false;
            }

            int pos = this.action.indexOf(' ');
            if(pos > 0) {
                String act = this.action.substring(pos + 1);
                this.limit = ScriptElement.parsePlaceHolder(act, availableParas, IConst.EMPTY_STR, null);
                if(this.limit == null || this.limit.length != 1) {
                    LOG.error("Invalid limit parameter under {}.{}", url.toString(), name());
                    return false;
                }
            }
            this.action = ACTION_GET;
        } else {
            LOG.error("Invalid action '{}' under {}.{}", this.action, url.toString(), name());
            return false;
        }

        return true;
    }
    
    /**
     * 解析表名称，因为LocSearchDB是固定的，所以在此将table解析独立出来
     * @param availableParas 接口中的可用字段
     * @param cfg 配置
     * @return 表名称
     */
    protected ScriptElement[] parseTable(Set<String> availableParas, Map<String, Object> cfg) {
        return parseScript(availableParas, cfg, CFG_TABLE);
    }
}