package cn.net.zhijian.mesh.dbworker;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsConnection;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker.AbsRDBWBuilder;
import cn.net.zhijian.mesh.frm.abs.AbsSearchDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 全文索引数据库，支持虚拟的多表处理，支持同步。核心是基于RDB实现。
 * 完成文档(不必一定是一个文档，只要是可以分成几个部分的文本内容都可以)的分词与搜索两个功能，
 * 中文分词功能基于ansj_seg，在文档录入数据库之前，先用ansj_seg做中文分词，然后去除无用内容，
 * 再存入sqlite，用内置的porter分词器再根据空格分词一次，应用其中的词根分词。
 * 搜索时，使用sqlite的全文搜索能力，多个词的情况，只支持and逻辑。
 * @author flyinmind
 */
public final class SearchDBWorker extends AbsSearchDBWorker {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String SEG_TABLE = "cls";
    private static final String SEG_DID = "did";
    private static final ReentrantLock InstanceLock = new ReentrantLock();

    @Override
    protected void initTables(AbsRDBWorker rdb) throws SQLException {
        /*
         * create virtual table if not exists __docs__ using fts4
         * (cls,did,title,summary,content,update_time,tokenize=porter)
         */
        StringBuilder builder = new StringBuilder(4096)
           .append("create virtual table if not exists ")
           .append(SEARCH_DOCS).append(" using fts4(")
           .append(SEG_TABLE).append(',')
           .append(SEG_DID).append(',')
           .append(SEARCH_SEG_TITLE).append(',')
           .append(SEARCH_SEG_SUMMARY).append(',')
           .append(SEARCH_SEG_CONTENT).append(',')
           .append(SEG_UPDATETIME)//update_time字段只是用来分析使用，无特殊含义
           .append(",tokenize=porter)");
        String sql =  builder.toString();
        try(AbsConnection conn = rdb.getWriteConn()){
            rdb.executeRawDDL(conn, sql);
        } catch (SQLException e) {
            LOG.error("Fail to execute {}", builder, e);
            throw e;
        } catch (MeshException e) {
            LOG.error("Fail to execute {}, io error", builder, e);
        } finally {
            rdb.sync(sql); //无论成功与否，都同步
        }
        rdb.setSysConfig(SEARCH_DB_VER, "0.1.0");
    }

    private SearchDBWorker(AbsRDBWorker rdbWorker) throws SQLException {
        super(rdbWorker);
    }

    public static AbsSearchDBWorker instance(int dbNo, AbsRDBWBuilder builder) {
        if(builder == null) {
            return null;
        }
        String id = dbId(builder.cid, builder.service, builder.db);
        AbsSearchDBWorker dw = DBWorkers.get(id);
        if(dw != null) {
            return dw;
        }

        InstanceLock.lock(); //注意：需要互斥的是new SearchDBWorker，而不是DBWorkers.put
        try {
            if((dw = DBWorkers.get(id)) == null) {//多线程并发的情况，再次判断
                AbsRDBWorker rdbWorker = AbsRDBWorker.instance(dbNo, builder);
                if(rdbWorker == null) {
                    LOG.error("Fail to get rdb worker of {}", id);
                    return null;
                }
                dw = new SearchDBWorker(rdbWorker);
                DBWorkers.put(id, dw);
            }
        } catch (SQLException e) {
            LOG.error("Fail to create db worker {}", id, e);
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

        LOG.debug("remove search db instance {}", id);
        DBWorkers.remove(id);
        return AbsRDBWorker.removeInstance(cid, service, dbName);
    }
    
    /**
     * 创建本地数据库实例
     * 因为是本地搜索器，所以一定基于sqlite实现
     * @param si 运行于其中的服务信息
     * @param db db名称
     * @param slaves 从节点
     * @return 查询数据库工人
     */
    public static AbsSearchDBWorker localInstance(ServiceInfo si, String db, NodeAddress[] slaves) {
        AbsRDBWorker rdbWorker = SqliteWorker.localInstance(si, db, slaves);
        if(rdbWorker == null) {
            LOG.error("Fail to get rdb worker of {}.{}", si.name, db);
            return null;
        }

        try {
            return new SearchDBWorker(rdbWorker);
        } catch (SQLException e) {
            LOG.error("Fail to create searchdb worker {}.{}", si.name, db, e);
            return null;
        }
    }

    /**
     * 保存或更新文档
     * 因为put时无法知道docid，所以先要查询table+did是否存在，若存在，则执行update操作
     * @param table 虚拟表名，用以区分不同的数据，不是真实的表
     * @param did 数据id
     * @param title 文档标题
     * @param summary 文档摘要
     * @param content 文档内容
     * @return 是否保存成功
     */
    boolean putDocument(String table, String did, String title, String summary, String content) {
        try (AbsConnection conn = rdb.getWriteConn()){
            String sql = "select docid from " + SEARCH_DOCS + " where "
                    + SEARCH_DOCS + " match('" + SEG_DID + ':' + did
                    + ' ' + SEG_TABLE + ':' + table + "')";
            Object[] result = rdb.queryLine(conn, sql);
            if(result != null && result.length > 0) {
                String docid = ValParser.parseString(result[0], "0");
                return updateDocument(conn, docid, title, summary, content);
            }
            return putDocument(conn, table, did, title, summary, content);
        } catch (SQLException | MeshException e) {
            LOG.error("Fail to save document,did:{}", did, e);
        }
        return false;
    }
    
    private boolean putDocument(AbsConnection conn, String table, String did, String title, String summary, String content) {
        int count = 0;
        StringBuilder sql = new StringBuilder(4096)
                .append("insert into ").append(SEARCH_DOCS)
                .append('(').append(SEG_TABLE)
                .append(',').append(SEG_DID)
                .append(',').append(SEG_UPDATETIME);
        StringBuilder values = new StringBuilder(4096).append("'")
                .append(table).append("','").append(did)
                .append("','").append(System.currentTimeMillis()).append('\'');

        if(title != null) {
            sql.append(',').append(SEARCH_SEG_TITLE);
            values.append(",'");
            splitWords(title, values);
            values.append('\'');
            count++;
        }

        if(summary != null) {
            sql.append(',').append(SEARCH_SEG_SUMMARY);
            values.append(",'");
            splitWords(summary, values);
            values.append('\'');
            count++;
        }

        if(content != null) {
            sql.append(',').append(SEARCH_SEG_CONTENT);
            values.append(",'");
            splitWords(content, values);
            values.append('\'');
            count++;
        }
        if(count == 0) {
            return true;
        }
        sql.append(") values(").append(values).append(')');
        String sqlStr = sql.toString();
        if(LOG.isDebugEnabled()) {
            LOG.debug("putDocument:{}", sqlStr);
        }
        return execute(conn, sqlStr) >= 0;
    }

    /**
     * 返回所有满足条件的document的id
     * 搜索条件全部‘和’关系，如果某个词只匹配前缀，请在后面加上"*"，
     * 另外所有查找字符串中不可以包含英文单引号、英文空格。
     * 英文查找不区分大小写
     * @param table 虚拟表名，用以区分不同的数据，不是真实的表
     * @param limit 返回的最大行数
     * @param args 查询参数
     * @return 数据id列表
     */
    List<Object> getDocuments(String table, int limit, String[] args) {
        StringBuilder sb = new StringBuilder(4096);

        sb.append("select ").append(SEG_DID).append(" from ").append(SEARCH_DOCS)
          .append(" where ").append(SEARCH_DOCS).append(" match('")
          .append(SEG_TABLE).append(':').append(table);
        for(String arg : args) {
            sb.append(' ');
            splitSearchWords(arg, sb);
        }
        sb.append("')");
        if(limit > 0) {
            sb.append(" limit ").append(limit);
        }
        String sqlStr = sb.toString();
        if(LOG.isDebugEnabled()) {
            LOG.debug("getDocuments:{}", sqlStr);
        }
        return rdb.querySingles(sqlStr);
    }
    
    /**
     * 删除文档
     * @param table 虚拟表名，用以区分不同的数据，不是真实的表
     * @param did 数据id
     * @return 是否删除成功
     */
    boolean removeDocument(String table, String did) {
        try(AbsConnection conn = rdb.getWriteConn()) {
            execute(conn, "delete from " + SEARCH_DOCS
                    + " where " + SEARCH_DOCS + " match('"
                    + SEG_DID + ':' + did + ' ' + SEG_TABLE + ':' + table + "')");
            return true;
        } catch (MeshException e) {
            LOG.error("Fail to save document,did:{}", did, e);
        }
        return false;
    }
    
    /**
     * @param table 虚拟表名，用以区分不同的数据，不是真实的表
     * @param req 请求
     * @param resp 前面步骤的响应内容，也可以向其中保存内容
     * @return 返回 did,title,summary
     */
    private HandleResult handleGet(String table, AbsServerRequest req, Map<String, Object> resp) {
        Map<String, Object> params = req.params();
        String s = ValParser.getAsStr(params, SEARCHDB_REQ_SEARCH);
        String[] args = s.split("\\s+");

        if(args.length == 0) {
            return new HandleResult(RetCode.WRONG_PARAMETER + 1);
        }

        int limit =  ValParser.getAsInt(params, SEARCHDB_REQ_LIMIT, 0);
        List<Object> docs = getDocuments(table, limit, args);
        if(docs != null) {
            if(docs.isEmpty()) {
                return new HandleResult(RetCode.NOT_EXISTS);
            }
            /*
             * 1）直接存resp，减少一次map的搬迁；
             * 服务端的处理中才可以使用，业务处理中，可能需要缓存，
             * 直接存入resp，会导致缓存错误
             * 2）返回的名称是固定的，是为了方便在端侧合并；
             */
            resp.put(SEARCHDB_RESP_DOCS, docs);
            return HandleResult.OK;
        }

        return HandleResult.InternalError;
    }

    private HandleResult handlePut(String table, AbsServerRequest req) {
        Map<String, Object> params = req.params();
        String did = ValParser.getAsStr(params, SEARCHDB_REQ_DID);
        String title = ValParser.getAsStr(params, SEARCHDB_REQ_TITLE);
        String summary = ValParser.getAsStr(params, SEARCHDB_REQ_SUMMARY);
        String content = ValParser.getAsStr(params, SEARCHDB_REQ_CONTENT);
        if(putDocument(table, did, title, summary, content)) {
            return HandleResult.OK;
        }
        return new HandleResult(RetCode.INTERNAL_ERROR, "fail to put");
    }
    
    private HandleResult handleUpdate(String table, AbsServerRequest req) {
        Map<String, Object> params = req.params();
        String did = ValParser.getAsStr(params, SEARCHDB_REQ_DID);
        //如果不存在，则返回空值，为了在update时，不影响未修改的部分
        String title = ValParser.getAsStr(params, SEARCHDB_REQ_TITLE, null);
        String summary = ValParser.getAsStr(params, SEARCHDB_REQ_SUMMARY, null);
        String content = ValParser.getAsStr(params, SEARCHDB_REQ_CONTENT, null);

        if(putDocument(table, did, title, summary, content)) {
            return HandleResult.OK;
        }
        return new HandleResult(RetCode.INTERNAL_ERROR, "fail to put");
    }
    
    private HandleResult handleRemove(String table, AbsServerRequest req) {
        String did = req.getString(SEARCHDB_REQ_DID);
        if(removeDocument(table, did)) {
            return HandleResult.OK;
        }
        return new HandleResult(RetCode.INTERNAL_ERROR, "fail to remove");
    }

    /**
     * 处理由业务发来的调用
     * @param req 请求体
     * @param resp 响应
     * @return 异步结果
     */
    @Override
    public HandleResult handleRequest(AbsServerRequest req, Map<String, Object> resp) {
        String action = req.getString(SEARCHDB_REQ_ACTION);
        String table = req.getString(SEARCHDB_REQ_TABLE);
        char a = Character.toLowerCase(action.charAt(0));
        if(a == 'g') { //get 第一个字符对上，就可以了
            return handleGet(table, req, resp);
        }
        
        if(!writable()) {
            return new HandleResult(RetCode.NO_RIGHT, "can't write db");
        }
        
        if(a == 'p') { //put
            return handlePut(table, req);
        } else if(a == 'u'){ //update
            return handleUpdate(table, req);
        } else if(a == 'r'){ //remove
            return handleRemove(table, req);
        }
        return HandleResult.ApiNotFound;
    }
}
