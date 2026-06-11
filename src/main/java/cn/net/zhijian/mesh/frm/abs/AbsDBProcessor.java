package cn.net.zhijian.mesh.frm.abs;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.DBClient;
import cn.net.zhijian.mesh.client.DBClient.DBReqBuilder;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement.EleType;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * DB处理的抽象父类
 * @author flyinmind of csdn.net
 *
 */
public abstract class AbsDBProcessor extends AbsProcessor implements IDBConst {
    private static final Logger LOG = LogUtil.getInstance();
   
    protected static final String CFG_DB = "db";
    protected static final String CFG_READSLAVE = "readSlave"; //读操作时，是否可以读从库
    protected static final String CFG_SHARDING = "sharding";

    //数据库的名称，如果是公司级服务，不能携带公司id
    //数据库后面的公司id，是在DBReqBuilder中产生请求体时加入的
    //webdb中不会在名称后面增加公司id，它只按参数处理。
    protected String db;
    protected boolean any = false; //任意一个成功，则结束，因为执行时没有并发，所以第一个成功则结束
    protected boolean hasWrite = false; //是否有写入操作，如果是，则需要启动事务，并且要做互斥
    //从库是否可以读取，如果true，且有从库，则读请求会分发到从库
    protected boolean readSlave = false;

    /**
     * 用于计算数据库分片的参数名称，
     * 最多支持MAX_SHARD个分片，每个分片对应一个数据库实例，也可以多个分片对应一个实例，
     * 在数据库逻辑中，还有分组概念，比如16分组，则MAX_SHARD被平均分成16个段，
     * 每个数据库实例上可以放1至多个分组
     */
    protected ScriptElement sharding = null;
    /**
     * 构建数据请求
     * @param req 请求
     * @param db 数据库名称
     * @param respData 响应
     * @param dbNo 数据库编号
     * @return 构建结果，如果是NO_OPERATION，则不必发送给webdb处理
     */
    protected abstract HandleResult buildRequest(AbsServerRequest req, String db, Map<String, Object> respData, int dbNo);

    public AbsDBProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        if(!super.parse(url, cfg, request)) {
            return false;
        }
        
        this.db = getDBConfig(cfg);
        if(StringUtil.isEmpty(this.db)) {
            LOG.error("Invalid webdb processor db under {}, {}", url.toString(), cfg);
            return false;
        }

        String s = ValParser.getAsStr(cfg, CFG_SHARDING).trim();
        if(!StringUtil.isEmpty(s)) { //不分库则可以不指定
            if(s.matches("^\\d+$")) { //一个固定的数字
                this.sharding = ScriptElement.create(s, EleType.NUM, IConst.EMPTY_STR, null);
            } else if(s.matches("(?i)^@\\{.+\\}$")) {
                this.sharding = ScriptElement.create(s, EleType.PARA, IConst.EMPTY_STR, null);
                if(this.sharding == null) {
                    LOG.error("Fail to parse {} under {}, sharding is null", CFG_SHARDING, url.toString());
                    return false;
                }

                List<String> paras = sharding.segments();
                int errNo = ScriptElement.checkParas(paras, super.availableParas(request));
                if(errNo >= 0) { //检查需要的请求参数是否都存在，响应参数未校验
                    LOG.error("Can't find the parameter `{}` in sharding config {} of {}", paras.get(errNo), s, url.toString());
                    return false;
                }
            } else {
                LOG.error("Fail to parse {} under {}, invalid sharding", CFG_SHARDING, url.toString());
                return false;
            }
        }
        //多个操作都是读操作时，可以指定只要任何一个成功则认为成功
        this.any = ValParser.getAsBool(cfg, DB_REQ_ANY, false);
        return true;
    }

    /**
     * 独立成为函数，便于在子类中重载，改变获取db名称的逻辑，
     * 比如在Bios MetaHandler中，返回固定值
     * @param cfg 配置
     * @return 库名称
     */
    protected String getDBConfig(Map<String, Object> cfg) {
        return ValParser.getAsStr(cfg, CFG_DB);
    }
    
    @Override
    protected Set<String> availableParas(RequestInfo ri) {
        Set<String> paras = super.availableParas(ri);
        if(this.sharding != null) {
            paras.add(IConst.EMBEDED_DB_SHARD);
        }
        return paras;
    }
    
    /**
     * 计算分库字段，此字段在配置语句中，脚本中可以通过@{#shard}获得
     * @param req 请求
     * @param resp 响应
     * @return -1表示无分库信息
     */
    protected int calculateShard(AbsServerRequest req, Map<String, Object> resp) {
        if(sharding == null) {
            return -1; //不分片
        }

        Object o = sharding.run(req, resp);
        long h = ValParser.parseLong(o, 0L);
        return saveShard(req, h);
    }
    
    protected int saveShard(AbsServerRequest req, long v) {
        int shardNo = (int)(ValParser.absLong(v) % MAX_SHARDING_NUM);
        /*
         * 存入req中，在buildRequest中需要使用它，替代{#shard}占位符，
         * 如果没有设置sharding参数，但是脚本中又用到，在请求执行时会发生错误。
         */
        req.params().put(IConst.EMBEDED_DB_SHARD, shardNo);
        return shardNo;
    }

    /**
     * 
     * @param req 请求
     * @param url url
     * @param db 数据库
     * @param respData 响应内容
     * @param dbNo 数据库实例编号
     * @return 异步请求结果
     */
    protected CompletableFuture<HandleResult> sendDBRequest(AbsServerRequest req,
            String url, String db, Map<String, Object> respData, int dbNo) {
        ServiceInfo caller = serviceInfo();
        if(LOG.isDebugEnabled()) {
            LOG.debug("sendDBRequest(url:{},db:{},traceId:{},cid:{})", url, db, req.traceId, req.cid());
        }
        HandleResult dbReqData = buildRequest(req, db, respData, dbNo);
        if(dbReqData.code != RetCode.OK || dbReqData.data == null) {
            if(dbReqData.code == RetCode.NO_OPERATION) { //无sql，但是仍然返回OK
                return CompletableFuture.completedFuture(HandleResult.OK);
            }
            return CompletableFuture.completedFuture(dbReqData);
        }
        /*
         * 计算分库字段，在sql语句中可以通过@{#shard}获得此字段。
         * 特别注意：受制于表结构的设计，TreeDB只能按dir分库，不能按'路径+key'分库。
         */
        int shardNo = calculateShard(req, respData);
        /*
         * 获取数据库编号
         * 相同dbNo的数据库可以支持多个sharding
         * 当后期数据量太大时，需要将该公司移出cid分片的分区。
         * 在使用cid分片的情况下，数据中仍然可以使用内置参数@{#shard}
         */
        DBReqBuilder builder = new DBReqBuilder(caller, db, dbNo)
                .shardId(shardNo)
                .readSlave(this.readSlave);
        builder.putAll(dbReqData.data)
               .put(DB_REQ_TIME, req.reqTime)
               .url(url)
               .cid(req.cid())
               .traceId(req.traceId);
        return DBClient.dbToken(builder).thenComposeAsync(token -> {
            if(StringUtil.isEmpty(token)) {
                LOG.error("Fail to get token of {}.{}", caller.name, builder.db);
                return futureResult(RetCode.NO_RIGHT);
            }
            builder.token(token);
            return DBClient.rdbPost(builder);
        }, Pool);
    }
    
    protected CompletableFuture<HandleResult> sendDBRequest(AbsServerRequest req,
            String url, String db, Map<String, Object> respData) {
        return req.getDbNo().thenComposeAsync(dbNo -> {
            if(dbNo < 0) {
                return futureResult(RetCode.NO_RIGHT, "db not ready");
            }
            return sendDBRequest(req, url, db, respData, dbNo);
        }, Pool);
    }

    protected static ScriptElement[] parseScript(Set<String> availableParas, Map<String, Object> cfg, String segName) {
        String s = ValParser.getAsStr(cfg, segName).trim();
        if(StringUtil.isEmpty(s)) { //没有配置相应字段
            return null;
        }
        return ScriptElement.parsePlaceHolder(s, availableParas, IConst.EMPTY_STR, null);
    }
}