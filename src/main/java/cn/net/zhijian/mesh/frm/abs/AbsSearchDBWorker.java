package cn.net.zhijian.mesh.frm.abs;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringSpliter;
import cn.net.zhijian.util.StringUtil;

/**
 * 全文索引数据库，支持虚拟的多表处理。只支持以sqlite为基础实现。
 * 没有update_time字段，所以同步时，因为并发的原因，可能会出现不一致的情况。
 * 完成文档分词与搜索两个功能，`文档`不是指一个文档，只要是可以分成几个部分的文本内容都可以。
 * 中文分词功能基于倒排索引与正排索引同时分词，在文档录入数据库之前，先做中文分词，然后去除无用内容，
 * 再存入sqlite，用内置的porter分词器再根据空格分词一次，应用其中的词根分词。
 * 搜索时，使用sqlite的全文搜索能力，多个词的情况，只支持and逻辑。
 * @author flyinmind
 */
public abstract class AbsSearchDBWorker extends AbsDBWorker {
    public static final String UNDEFINED_TABLE = "u"; //undefined

    private static final Logger LOG = LogUtil.getInstance();
    protected static final String SEARCH_DB_VER = "search_db_ver";
    protected static final String SEARCH_BLANK_APPEND = " ";
    protected static final String SEARCH_APPEND = "*";

    protected static final String SEARCH_SEG_TITLE = "title";
    protected static final String SEARCH_SEG_SUMMARY = "summary";
    protected static final String SEARCH_SEG_CONTENT = "content";
    
    protected static final Map<String, AbsSearchDBWorker> DBWorkers = new ConcurrentHashMap<>();
    
    protected final AbsRDBWorker rdb;
    
    /**
     * 处理由业务发来的调用
     * @param req 请求体
     * @param resp 响应
     * @return 异步结果
     */
    public abstract HandleResult handleRequest(AbsServerRequest req, Map<String, Object> resp);
    protected abstract void initTables(AbsRDBWorker rdbWorker) throws SQLException;

    protected AbsSearchDBWorker(AbsRDBWorker rdbWorker) throws SQLException {
        this.rdb = rdbWorker;
        initTables(rdbWorker);
    }

    public AbsRDBWorker getCoreDb() {
        return this.rdb;
    }
    
    public static void init(String confDir) {
        if(StringSpliter.initialized()) {
            return;
        }

        //分词初始化很耗时，放在线程中，不堵塞主线程
        //集群环境，实例中不一定有webdb服务，但是可能会使用SearchDB
        //所以不能放在webdb的watcher中初始化
        new Thread("word_spliter_loader") {
            public void run() {
                String dicPath = FileUtil.addPath(confDir, "dictionary.txt");
                LOG.debug("load default dictionary from {}", dicPath);
                try {
                    StringSpliter.init(dicPath, 2);
                } catch (IOException e) {
                    LOG.error("Fail to load {}", dicPath, e);
                }
            }
        }.start();
    }
    /**
     * 销毁实例
     */
    public static void destroy() {
        for(Map.Entry<String, AbsSearchDBWorker> one : DBWorkers.entrySet()) {
            AbsSearchDBWorker db = one.getValue();
            LOG.info("Close search db {}", one.getKey());
            try {
                db.close();
            } catch (Exception e) {
                LOG.error("Fail to close searchdb instance of {}", one.getKey(), e);
            }
        }
        DBWorkers.clear();
    }

    /**
     * 直接查找实例，不考虑创建
     * @param service 所属服务的名称
     * @param db 数据库名称
     */
    public static AbsSearchDBWorker instance(int cid, String service, String db) {
        String id = dbId(cid, service, db);
        return DBWorkers.get(id);
    }

    /**
     * ansj中文分词，包括英文分词能力，分词后，输出以空格分隔的字符串。
     * 写入sqlite时，再次做porter分词，将英文部分转为词根，并转小写。
     * 每个字段都会删除单引号`'`，否则无法适应sql的语法
     * @param src 源
     * @param sb 存放的内存
     */
    public static void splitWords(String src, StringBuilder sb) {
        Set<String> kws = StringSpliter.getKeyWords(src);
        int n = 0;
        for(String s : kws) {
            if(n > 0) {
                sb.append(SEARCH_BLANK_APPEND);
            }
            StringUtil.replaceChars(s, SQL_QUOTE, SEARCH_BLANK_APPEND, sb);
            n++;
        }
    }

    protected static void splitSearchWords(String src, StringBuilder sb) {
        Set<String> kws = StringSpliter.getKeyWords(src);
        int n = 0;
        for(String s : kws) {
            if(n > 0) {
                sb.append(' ');
            }
            StringUtil.replaceChars(s, SQL_QUOTE, SEARCH_APPEND, sb);
            sb.append(SEARCH_APPEND);
            n++;
        }
    }
    
    protected int execute(AbsConnection conn, String sql) {
        boolean execOk = false;
        int affectedRowNum = -1;
        if(LOG.isDebugEnabled()) {
            LOG.debug("Search:{}", sql);
        }
        try {
            rdb.beginTransaction(conn);
            affectedRowNum = rdb.executeRawDML(conn, sql);
            execOk = true;
        } catch (SQLException e) {
            LOG.error("Fail to execute {} in {}", sql, rdb.name(), e);
        } finally {
            rdb.endTransaction(conn, execOk);
            if(execOk) {
                rdb.sync(sql);
            }
        }
        return affectedRowNum;
    }
    
    public boolean writable() {
        return rdb.writable();
    }

    @Override
    public void close() {
        try {
            rdb.close();
        } catch (IOException ignored) {
        }
    }
    
    /**
     * 更新数据
     * @param conn sqlite数据库连接
     * @param docid 文档id
     * @param title 标题
     * @param summary 摘要
     * @param content 内容
     * @return 成功则返回true
     */
    protected boolean updateDocument(AbsConnection conn, String docid, String title, String summary, String content) {
        int count = 0;
        StringBuilder sql = new StringBuilder(4096)
            .append("update ")
            .append(SEARCH_DOCS).append(" set ")
            .append(SEG_UPDATETIME).append("='")
            .append(System.currentTimeMillis())
            .append('\'');

        if(title != null) { //可以接受长度为0的字符串
            sql.append(',').append("title='");
            splitWords(title, sql);
            sql.append('\'');
            count++;
        }

        if(summary != null) {
            sql.append(',').append("summary='");
            splitWords(summary, sql);
            sql.append('\'');
            count++;
        }

        if(content != null) {
            sql.append(',').append("content='");
            splitWords(content, sql);
            sql.append('\'');
            count++;
        }

        if(count == 0) {
            return true; //没有做任何更新，直接返回
        }
        sql.append(" where docid=").append(docid);

        return execute(conn, sql.toString()) >= 0;
    }

    @Override
    public String type() {
        return SEARCHDB;
    }
    
    @Override
    public final String name() {
        return rdb.name();
    }
}
