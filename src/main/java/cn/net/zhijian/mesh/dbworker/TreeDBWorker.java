package cn.net.zhijian.mesh.dbworker;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsConnection;
import cn.net.zhijian.mesh.frm.abs.AbsDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker.AbsRDBWBuilder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * Record a tree structure in a RDB databas.
 * There is no data synchronization, it all depends on RDB to synchronize and backup data
 * Restricted by the design of TreeDB, it is only can be divided by 'dir',
 * can't be divided by 'item name' or 'item path'
 * @author flyinmind
 */
public final class TreeDBWorker extends AbsDBWorker {
    private static final Logger LOG = LogUtil.getInstance();

    public static final String ROOT_DIR = "/";

    private static final String TABLE_ITEM = "item";
    private static final String TABLE_DIR = "dir";
    private static final String TREE_DB_VER = "tree_db_ver";

    private static final ReentrantLock InstanceLock = new ReentrantLock();
    private static final Map<String, TreeDBWorker> DBWorkers = new ConcurrentHashMap<>();
    private static final Pattern NAME_CHECKER = Pattern.compile("^[a-zA-Z0-9_:\\.]{1,255}$");

    final AbsRDBWorker rdb;

    @Override
    public String type() {
        return TREEDB;
    }
    
    @Override
    public String name() {
        return rdb.name();
    }
    
    public AbsRDBWorker getCoreDb() {
        return this.rdb;
    }
    
    public enum DBResultCode {
        OK, ALREADY_EXISTS, NOT_EXISTS, NOT_EMPTY,
        PARENT_NOT_EXISTS, INSERT_FAILED, WRONG_FORMAT,
        INVALID_ACTION, READ_ONLY, UNKOWN_ERROR,
        EXECUTE_FAILED, WRONG_PARA
    }
    
    private void initTables() throws Exception {
        String[] oprs = new String[] {
            "create table if not exists " + TABLE_DIR
            + "(id      bigint(8) not null primary key,"
            + "fid      bigint(8) not null,"
            + "name     varchar(255) not null)",

            "create index if not exists idx_dir_fid on "
            + TABLE_DIR + "(fid)",

            "create table if not exists " + TABLE_ITEM
            + "(fid     bigint(8) not null,"
            + "name     varchar(255) not null,"
            + "val      text not null,"
            + "primary key(fid, name))"
        };

        try(AbsConnection conn = rdb.getWriteConn()) {
            rdb.executeDDLSqls(conn, oprs);
        } catch (SQLException e) {
            LOG.error("Init tree db failed", e);
            throw e;
        }

        // create root dir
        long id = keyId(ROOT_DIR);
        if(!dirExists(id)) {
            execute("insert into " + TABLE_DIR + "(fid,id,name," + SEG_UPDATETIME + ") values(0,"
                    + id + ",'" + ROOT_DIR + "'," + System.currentTimeMillis() + ")");
        }
        rdb.setSysConfig(TREE_DB_VER, "0.1.0");
    }

    private TreeDBWorker(AbsRDBWorker rdbWorker) throws Exception {
        this.rdb = rdbWorker;
        initTables();
    }

    public static TreeDBWorker instance(int dbNo, AbsRDBWBuilder builder) {
        if(builder == null) {
            return null;
        }
        
        String id = dbId(builder.cid, builder.service, builder.db);
        TreeDBWorker dw = DBWorkers.get(id);
        if(dw != null) {
            return dw;
        }
        
        InstanceLock.lock(); //Alert:'new TreeDBWorker' need synchronization, not DBWorkers.put
        try {
            if((dw = DBWorkers.get(id)) == null) {//judge again in multiple threads
                AbsRDBWorker rdbWorker = AbsRDBWorker.instance(dbNo, builder);
                if(rdbWorker == null) {
                    LOG.error("Fail to get rdb worker of {}", id);
                    return null;
                }
                dw = new TreeDBWorker(rdbWorker);
                DBWorkers.put(id, dw);
            }
        } catch (Exception e) {
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

        LOG.debug("remove tree db instance {}", id);
        DBWorkers.remove(id);
        return AbsRDBWorker.removeInstance(cid, service, dbName);
    }

    /**
     * local treedb, use sqlite as base database
     * @param si service infomation
     * @param db database name
     * @param slaves slave nodes
     * @return treedb worker
     */
    public static TreeDBWorker localInstance(ServiceInfo si, String db, NodeAddress[] slaves) {
        AbsRDBWorker rdbWorker = SqliteWorker.localInstance(si, db, slaves);
        if(rdbWorker == null) {
            LOG.error("Fail to get rdb worker of {}.{}", si.name, db);
            return null;
        }

        try {
            return new TreeDBWorker(rdbWorker);
        } catch (Exception e) {
            LOG.error("Fail to create treedb worker {}.{}", si.name, db, e);
            return null;
        }
    }
    
    public static TreeDBWorker instance(int cid, String service, String dbName) {
        String id = dbId(cid, service, dbName);
        return DBWorkers.get(id);
    }

    /**
     * 销毁实例
     */
    public static void destroy() {
        for(Map.Entry<String, TreeDBWorker> one : DBWorkers.entrySet()) {
            TreeDBWorker db = one.getValue();
            LOG.info("Close tree db {}", one.getKey());
            try {
                db.close();
            } catch (Exception e) {
                LOG.error("Fail to close treedb instance of {}", one.getKey(), e);
            }
        }
        DBWorkers.clear();
    }

    public boolean writable() {
        return rdb.writable();
    }
    
    /**
     * Insert or update a item.
     * @param item  item name, can NOT be ended by '/'
     * @param val must be a normal string
     * @param ut update time
     * @return result
     */
    public DBResultCode put(String item, final String val, long ut) {
        int pos = item.lastIndexOf('/');
        String dir = pos == 0 ? ROOT_DIR : item.substring(0, pos);
        String name = item.substring(pos + 1);
        
        if(!NAME_CHECKER.matcher(name).matches()) {
            LOG.warn("put, invalid name: {}", name);
            return DBResultCode.WRONG_FORMAT;
        }
        
        long fid = keyId(dir);
        if (!dirExists(fid)) {
            LOG.warn("put, dir {} not exists", dir);
            return DBResultCode.PARENT_NOT_EXISTS;
        }

        return writeItem(fid, name, val, ut);
    }
    
    /**
     * 如果数据库中的值与请求方持有的原值一致，则将数据库中的更新为请求方设置的新值，
     * 否则保持原样，请求方应使用函数返回的值作为新的当前值。
     * 判断与设置，在同一个事务中完成，所以可以保证原子性
     * 此接口不能在接口配置中使用，因为它返回内容，与其他PUT接口不同
     * @param item 更新项
     * @param val 请求方希望设置的新值
     * @param oldVal 请求方当前的值
     * @param ut 更新时间
     * @return 最新的值
     */
    public ItemInfo putAtomic(String item, final String val, final String oldVal, long ut) {
        int pos = item.lastIndexOf('/');
        String dir = pos == 0 ? ROOT_DIR : item.substring(0, pos);
        String name = item.substring(pos + 1);

        if(!NAME_CHECKER.matcher(name).matches()) {
            LOG.warn("putAtomic, invalid name: {}", name);
            return null;
        }
        
        long fid = keyId(dir);
        if (!dirExists(fid)) {
            LOG.warn("putAtomic, dir {} not exists", dir);
            return null;
        }
        
        boolean execOk = false;
        AbsConnection conn = null;
        String sql = null; //sql为空时，execOk一定为false，所以不会触发sync

        try {
            conn = rdb.getWriteConn();
            rdb.beginTransaction(conn);
            Object[] line = rdb.queryLine(conn, SQL_GET_PREFIX + "fid=" + fid + " and name='" + name + "'");
            if (line != null && line.length >= 2) {
                ItemInfo cur = new ItemInfo(name, line);
                if(!cur.val.equals(oldVal)) {
                    return cur; //原值与数据库不一致，则直接返回数据库中的值
                }
            }
            StringBuilder sb = new StringBuilder(4096);
            
            sb.append(SQL_WRITEITEM_PREFIX).append(fid).append(",'");
            StringUtil.replaceChars(name, '\'', "''", sb);
            sb.append("','");
            StringUtil.replaceChars(val, '\'', "''", sb);
            sb.append("',").append(ut).append(')');
            sql = sb.toString();
            
            rdb.executeRawDML(conn, sql);
            execOk = true;
            return new ItemInfo(name, val, System.currentTimeMillis());
        } catch (SQLException | MeshException e) {
            LOG.error("Fail to get connection in {} when execute puts", rdb.name(), e);
            return null;
        } finally {
            if(conn != null) {
                rdb.endTransaction(conn, execOk);
                try {
                    conn.close();
                } catch (Exception e) {
                    LOG.error("Fail to close connection", e);
                }
                if(execOk) {
                    rdb.sync(sql);
                }
            }
        }
    }

    private static final String SQL_SELECTVAL_PREFIX = "select val from " + TABLE_ITEM + " where ";
    /**
     * Insert or update a map item.
     * val is Map<String,Object>, only segments in map will be set.
     * Other segments will not be changed.
     * @param item  item name, must NOT be ended by '/'
     * @param val val in the map
     * @param ut update time
     * @return result
     */
    public DBResultCode putMap(String item, Map<String, Object> val, long ut) {
        if(val == null || val.isEmpty()) {
            return DBResultCode.WRONG_PARA;
        }
        int pos = item.lastIndexOf('/');
        String dir = pos == 0 ? ROOT_DIR : item.substring(0, pos);
        String name = item.substring(pos + 1);
        
        if(!NAME_CHECKER.matcher(name).matches()) {
            LOG.warn("putMap, invalid name: {}", name);
            return DBResultCode.WRONG_FORMAT;
        }
        
        long fid = keyId(dir);
        if (!dirExists(fid)) {
            LOG.warn("putMap, dir {} not exists", dir);
            return DBResultCode.PARENT_NOT_EXISTS;
        }
        
        Map<String, Object> mapVal = null;
        String sql = SQL_SELECTVAL_PREFIX + "fid=" + fid + " and name='" + name + '\'';
        Object[] l = rdb.queryLine(sql);
        
        if (l != null) {
            mapVal = JsonUtil.jsonToMap(ValParser.parseString(l[0]));
        }
        if(mapVal == null) {
            mapVal = new HashMap<>();
        }
        
        //set all segments into the old value
        mapVal.putAll(val);
        String s = JsonUtil.objToJson(mapVal);
        return writeItem(fid, name, s, ut);
    }
    
    /**
     * Insert or update a array map item.
     * val is a String list, only segments in array will be set.
     * Other segments will not be changed.
     * @param item  item name, can not be ended by '/'
     * @param val k-v pairs
     * @param ut update time
     * @return result
     */
    public DBResultCode putList(String item, String val, long ut) {
        if(StringUtil.isEmpty(val)) {
            return DBResultCode.WRONG_PARA;
        }
        int pos = item.lastIndexOf('/');
        String dir = pos == 0 ? ROOT_DIR : item.substring(0, pos);
        String name = item.substring(pos + 1);
        
        if(!NAME_CHECKER.matcher(name).matches()) {
            LOG.warn("putList, invalid name: {}", name);
            return DBResultCode.WRONG_FORMAT;
        }
        
        long fid = keyId(dir);
        if (!dirExists(fid)) {
            LOG.warn("putList, dir {} not exists", dir);
            return DBResultCode.PARENT_NOT_EXISTS;
        }
        
        List<Object> list = null;
        String sql = SQL_SELECTVAL_PREFIX + "fid=" + fid + " and name='" + name + "'";
        
        Object[] l = rdb.queryLine(sql);
        if (l != null) {
            list = JsonUtil.jsonToList(ValParser.parseString(l[0]));
        }

        if(list != null){
            if(list.contains(val)) { //already exists, just return OK
                return DBResultCode.OK;
            }
        } else {
            list = new ArrayList<>();
        }
        
        list.add(val);
        String s = JsonUtil.objToJson(list);
        
        return writeItem(fid, name, s, ut);
    }

    private static final String SQL_ITEMEXISTS_PREFIX = "select * from " + TABLE_ITEM + " where ";
    /**
     * write the item if it not exists
     * @param item item name
     * @param val item value
     * @param ut update time
     * @return result
     */
    public DBResultCode putIfAbsent(String item, final String val, long ut) {
        int pos = item.lastIndexOf('/');
        String dir = pos == 0 ? ROOT_DIR : item.substring(0, pos);
        String name = item.substring(pos + 1);
        
        if(!NAME_CHECKER.matcher(name).matches()) {
            LOG.warn("putIfAbsent, invalid name: {}", name);
            return DBResultCode.WRONG_FORMAT;
        }
        
        long fid = keyId(dir);
        if (!dirExists(fid)) {
            LOG.warn("putIfAbsent, dir {} not exists", dir);
            return DBResultCode.PARENT_NOT_EXISTS;
        }
        
        if(exists(SQL_ITEMEXISTS_PREFIX + "fid=" + fid + " and name='" + name + "'")) {
            return DBResultCode.ALREADY_EXISTS;
        }
        return writeItem(fid, name, val, ut);
    }
    
    /**
     * Insert or update items under a dir.
     * @param dir path to the item, can't be ended by '/'
     * @param val k-v pairs
     * @param ut update time
     * @return result
     */
    public DBResultCode puts(String dir, final Map<String, Object> val, long ut) {
        long fid = keyId(dir);
        if (!dirExists(fid)) {
            LOG.warn("puts, dir {} not exists", dir);
            return DBResultCode.PARENT_NOT_EXISTS;
        }
        
        boolean execOk = false;
        AbsConnection conn = null;
        StringBuilder sb = new StringBuilder(4096);
        List<String> sqls = new ArrayList<>(val.size());

        try {
            conn = rdb.getWriteConn();
            rdb.beginTransaction(conn);
            //set all new segments into old value
            for (Map.Entry<String, Object> e : val.entrySet()) {
                String name = e.getKey();
                if(!NAME_CHECKER.matcher(name).matches()) {
                    LOG.warn("puts, invalid name: {}", name);
                    return DBResultCode.WRONG_FORMAT;
                }
                String v = ValParser.parseString(e.getValue());
                
                sb.delete(0, sb.length());
                sb.append(SQL_WRITEITEM_PREFIX).append(fid).append(",'");
                StringUtil.replaceChars(name, '\'', "''", sb);
                sb.append("','");
                StringUtil.replaceChars(v, '\'', "''", sb);
                sb.append("',").append(ut).append(')');
                
                String sql = sb.toString();
                
                sqls.add(sql);
                rdb.executeRawDML(conn, sql);
            }
            execOk = true;
        } catch (SQLException | MeshException e) {
            LOG.error("Fail to get connection in {} when execute puts", rdb.name(), e);
            return DBResultCode.INSERT_FAILED;
        } finally {
            if(conn != null) {
                rdb.endTransaction(conn, execOk);
                try {
                    conn.close();
                } catch (Exception e) {
                    LOG.error("Fail to close connection", e);
                }
                if(execOk) {
                    rdb.sync(sqls);
                }
            }
        }
        return DBResultCode.OK;
    }
    
    private static final String SQL_WRITEITEM_PREFIX = "replace into " + TABLE_ITEM 
            + "(fid,name,val," + SEG_UPDATETIME + ") values(";
    private DBResultCode writeItem(long fid, String name, String v, long ut) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append(SQL_WRITEITEM_PREFIX).append(fid).append(",'");
        StringUtil.replaceChars(name, '\'', "''", sb);
        sb.append("','");
        StringUtil.replaceChars(v, '\'', "''", sb);
        sb.append("',").append(ut).append(')');
        return execute(sb.toString());
    }
    
    private DBResultCode execute(String sql) {
        boolean execOk = false;
        AbsConnection conn = null;
        
        try {//because of transaction, can't use `try resource with`
            conn = rdb.getWriteConn();
            rdb.beginTransaction(conn);
            rdb.executeRawDML(conn, sql);
            execOk = true;
        } catch (SQLException | MeshException e) {
            LOG.error("Fail to execute {} in {}", sql, rdb.name(), e);
            return DBResultCode.EXECUTE_FAILED;
        } finally {
            if(conn != null) {
                rdb.endTransaction(conn, execOk);
                if(execOk) {
                    rdb.sync(sql);
                }
                try {
                    conn.close();
                } catch (Exception e) {
                    LOG.error("Fail to close connection", e);
                }
            }
        }
        return DBResultCode.OK;
    }

    private static final String SQL_GET_PREFIX = "select val," + SEG_UPDATETIME + " from " + TABLE_ITEM + " where ";
    /**
     *
     * @param item item's name includes dir name, example,
     *       /root/config/itemName, can not be ended by '/'
     * @return item info
     */
    public ItemInfo get(String item) {
        int pos = item.lastIndexOf('/');
        String dir = pos == 0 ? ROOT_DIR : item.substring(0, pos);
        String name = item.substring(pos + 1);
        long fid = keyId(dir);
        
        String sql = SQL_GET_PREFIX + "fid=" + fid + " and name='" + name + "'";

        Object[] line = rdb.queryLine(sql);
        if (line != null && line.length >= 2) {
            return new ItemInfo(name, line);
        }
        
        if(LOG.isDebugEnabled()) {
            LOG.debug("Item {} not exists, dir:{}, name:{}, fid:{}", item, dir, name, fid);
        }
        
        return null;
    }
    
    public String getValue(String item) {
        ItemInfo ii = get(item);
        if(ii == null) {
            return null;
        }
        return ii.val;
    }
    
    /**
     * get value as a map
     * @param item item name, must not be ended by '/'
     * @return map
     */
    public Map<String, Object> getMap(String item) {
        ItemInfo ii = get(item);
        if(ii == null) {
            return null;
        }
        return JsonUtil.jsonToMap(ii.val);
    }
    
    private static final String SQL_GETS_PREFIX = "select name,val," + SEG_UPDATETIME + " from " + TABLE_ITEM + " where ";
    /**
     * get all items under a dir
     * @param dir can not be ended by '/'
     * @return items
     */
    public List<ItemInfo> gets(String dir) {
        long fid = keyId(dir);
        String sql = SQL_GETS_PREFIX + "fid=" + fid;

        List<ItemInfo> arr = new ArrayList<>();
        List<Object[]> lines = rdb.queryArrays(sql);
        if(lines == null || lines.isEmpty()) {
            return arr; //不返回空
        }
        
        for(Object[] l : lines) {
            arr.add(new ItemInfo(ValParser.parseString(l[0]),
                    ValParser.parseString(l[1]),
                    ValParser.parseLong(l[2], 0)));
        }
        return arr;
    }
    
    /**
     * get all items under a dir which are newer than lastUpdateTime
     * 
     * @param dir can not be ended by '/'
     * @param fromTime oldest update time
     * @return items
     */
    public List<ItemInfo> gets(String dir, long fromTime) {
        long fid = keyId(dir);
        List<ItemInfo> arr = new ArrayList<>();
        String sql = SQL_GETS_PREFIX + "fid=" + fid + " and " + SEG_UPDATETIME + '>' + fromTime;
        List<Object[]> lines = rdb.queryArrays(sql);
        if(lines == null || lines.isEmpty()) {
            return arr;
        }
        
        for(Object[] l : lines) {
            arr.add(new ItemInfo(ValParser.parseString(l[0]),
                    ValParser.parseString(l[1]),
                    ValParser.parseLong(l[2], 0)));
        }
        return arr;
    }
    
    /**
     * get all items under a dir
     * for example, get all sub dir and their items
     * under dir `/serivce/config/dbs`
     * @param dir can not be ended by '/'
     * @return items
     */
    public List<Map<String,String>> getSubsAndItems(String dir) {
        long id = keyId(dir);
        List<Map<String,String>> arr = new ArrayList<>();
        String sql = "select d.name,i.name,i.val from dir d,item i where d.fid="
        + id + " and i.fid=d.id";
        List<Object[]> lines = rdb.queryArrays(sql);
        if(lines == null || lines.isEmpty()) {
            return arr;
        }
        
        for(Object[] l : lines) {
            Map<String, String> line = new HashMap<>();
            line.put("name", ValParser.parseString(l[0]));
            line.put("key", ValParser.parseString(l[1]));
            line.put("val", ValParser.parseString(l[2]));
            arr.add(line);
        }
        return arr;
    }

    private static final String SQL_RMVITEM_PREFIX = "delete from " + TABLE_ITEM + " where ";
    /**
     * remove a item
     * @param item path to the item, can't be ended by '/'
     * @param ut update time
     * @return result
     */
    public DBResultCode remove(String item, long ut) {
        int pos = item.lastIndexOf('/');
        String dir = pos == 0 ? ROOT_DIR : item.substring(0, pos);
        String name = item.substring(pos + 1);
        long fid = keyId(dir);

        return execute(SQL_RMVITEM_PREFIX + "fid=" + fid + " and name='"
                + name + "' and " + SEG_UPDATETIME + '<' + ut);
    }
    
    /**
     * remove an element from the list
     * @param item item name
     * @param val value
     * @param ut update time
     * @return result
     */
    public DBResultCode removeFromList(String item, String val, long ut) {
        int pos = item.lastIndexOf('/');
        String dir = pos == 0 ? ROOT_DIR : item.substring(0, pos);
        String name = item.substring(pos + 1);
        
        if(!NAME_CHECKER.matcher(name).matches()) {
            LOG.warn("removeFromList, invalid name: {}", name);
            return DBResultCode.WRONG_FORMAT;
        }
        
        long fid = keyId(dir);
        if (!dirExists(fid)) {
            LOG.warn("removeFromList, dir {} not exists", dir);
            return DBResultCode.PARENT_NOT_EXISTS;
        }
        
        List<Object> list = null;
        String sql = SQL_SELECTVAL_PREFIX + "fid=" + fid + " and name='" + name + "'";

        Object[] l = rdb.queryLine(sql);
        if (l != null && l.length > 0) {
            list = JsonUtil.jsonToList(ValParser.parseString(l[0]));
        }
        
        if(list == null || list.isEmpty()){
            return DBResultCode.OK;
        }
        list.remove(val);
        String s = JsonUtil.objToJson(list);
        return writeItem(fid, name, s, ut);
    }
    
    /**
     * remove val from a map
     * @param item can not be ended by '/'
     * @param key segment name in the map
     * @param ut update time
     * @return reulst
     */
    public DBResultCode removeFromMap(String item, String key, long ut) {
        int pos = item.lastIndexOf('/');
        String dir = pos == 0 ? ROOT_DIR : item.substring(0, pos);
        String name = item.substring(pos + 1);
        
        if(!NAME_CHECKER.matcher(name).matches()) {
            LOG.warn("removeFromMap, invalid name: {}", name);
            return DBResultCode.WRONG_FORMAT;
        }
        
        long fid = keyId(dir);
        if (!dirExists(fid)) {
            LOG.warn("removeFromMap, dir {} not exists", dir);
            return DBResultCode.PARENT_NOT_EXISTS;
        }
        
        Map<String, Object> mapVal = null;
        String sql = SQL_SELECTVAL_PREFIX + "fid=" + fid + " and name='" + name + "'";

        Object[] l = rdb.queryLine(sql);
        if (l != null && l.length > 0) {
            mapVal = JsonUtil.jsonToMap(ValParser.parseString(l[0]));
        }
        
        if(mapVal == null || mapVal.isEmpty()){
            return DBResultCode.OK;
        }
        mapVal.remove(key);    
        String s = JsonUtil.objToJson(mapVal);
        return writeItem(fid, name, s, ut);
    }

    /**
     * Remove all items under a dir. 
     * If a item is younger than client time, will not be removed
     * @param dir can not be ended by '/'
     * @param ut Client time
     * @return result
     */
    public DBResultCode removes(String dir, long ut) {
        long fid = keyId(dir);
        return execute(SQL_RMVITEM_PREFIX + "fid=" + fid + " and " + SEG_UPDATETIME + '<' + ut);
    }

    private static final String SQL_INSERTDIR_PREFIX = "insert into " + TABLE_DIR + "(fid,id,name," + SEG_UPDATETIME + ") values(";
    /**
     * create a dir, if start with digit, the format is 'parent dir id/name'
     * otherwise, /parent dir name/dir
     * @param dir can not be ended by '/'
     * @param ut  update time
     * @return result
     */
    public DBResultCode createDir(String dir, long ut) {
        char firstCh = dir.charAt(0);
        int pos = dir.lastIndexOf('/');
        long fid;
        long id;
        String name;
        String parent;
        String fullDir;
        
        if(Character.isDigit(firstCh)) { //start with parent dir's id
            fid = Long.parseLong(dir.substring(0, pos));
            parent = dirName(fid);
            if(parent == null || parent.isEmpty()) {
                return DBResultCode.PARENT_NOT_EXISTS;
            }
            name = dir.substring(pos + 1); //skip '/'
            fullDir = parent + '/' + name;
        } else {
            parent = pos == 0 ? ROOT_DIR : dir.substring(0, pos);
            fid = keyId(parent);
            if (!dirExists(fid)) {
                LOG.error("Parent dir {} not exists", parent);
                return DBResultCode.PARENT_NOT_EXISTS;
            }
            name = dir.substring(pos + 1);
            fullDir = dir;
        }
        if(!NAME_CHECKER.matcher(name).matches()) {
            LOG.warn("Invalid dir name: {}", name);
            return DBResultCode.WRONG_FORMAT;
        }
        id = keyId(fullDir);
        if (dirExists(id)) {
            LOG.debug("Dir {} already exists", dir);
            return DBResultCode.OK; // don't care, just return OK
        }
        StringBuilder sb = new StringBuilder(4096);
        sb.append(SQL_INSERTDIR_PREFIX)
          .append(fid).append(',').append(id).append(",'");
        StringUtil.replaceChars(fullDir, '\'', "''", sb);
        sb.append("',").append(ut).append(')');

        return execute(sb.toString());
    }

    /**
     * list all sub-dirs under a dir
     * 
     * @param dir can not be ended by '/'
     * @return all sub dirs
     */
    public List<DirInfo> list(String dir) {
        long fid = keyId(dir);
        List<DirInfo> arr = new ArrayList<>();
        String sql = "select id,name," + SEG_UPDATETIME + " from " + TABLE_DIR + " where fid=" + fid;
        
        List<Object[]> lines = rdb.queryArrays(sql);
        if(lines == null || lines.isEmpty()) {
            return arr;
        }

        for(Object[] l : lines) {
            arr.add(new DirInfo(ValParser.parseLong(l[0], 0L),
                    ValParser.parseString(l[1]),
                    ValParser.parseLong(l[2], 0L)));
        }
        return arr;
    }
    
    /**
     * list all sub-dirs under a dir, and all items under each sub-dir
     * 
     * @param dir path to the item, can't be ended by '/'
     * @return sub items
     */
    public Map<String, Map<String, String>> getSubs(String dir) {
        long fid = keyId(dir);
        Map<String, Map<String, String>> arr = new HashMap<>();
        String sql = "select id,name from " + TABLE_DIR + " where fid=" + fid;
        
        List<Object[]> subDirs = rdb.queryArrays(sql);
        if(subDirs == null || subDirs.isEmpty()) {
            return arr;
        }
        
        Map<String, String> map;
        String name;
        String sqlItems = "select name,val from " + TABLE_ITEM + " where fid=";
        for(Object[] sub : subDirs) {
            List<Object[]> items = rdb.queryArrays(sqlItems + ValParser.parseLong(sub[0], -1));
            if(items == null || items.isEmpty()) {
                return arr;
            }
            
            map = new HashMap<>();
            for(Object[] item : items) {
                map.put(ValParser.parseString(item[0]), ValParser.parseString(item[1]));
            }
            name = getName(ValParser.parseString(sub[1])); //只记录最后一层目录的名字
            arr.put(name, map);
        }
        return arr;
    }
    
    /**
     * list all items' name under a dir
     * 
     * @param dir can not be ended by '/'
     * @return name list
     */
    public List<Object> names(String dir) {
        long fid = keyId(dir);
        String sql = "select name from " + TABLE_ITEM + " where fid=" + fid;
        
        List<Object> lines = rdb.querySingles(sql);
        if(lines == null) {
            return new ArrayList<>();
        }
        return lines;
    }

    /**
     * list all sub-dirs under a parent dir
     * @param dir path
     * @param ut update time
     * @return reulst
     */
    public DBResultCode removeDir(String dir, long ut) {
        char firstCh = dir.charAt(0);
        long id;
        
        //starts with dir id, normal dir must start with '/'
        if(Character.isDigit(firstCh)) { 
            id = Long.parseLong(dir);
        } else {
            id = keyId(dir);
        }
        
        if (hasSubDir(id) || hasSubItem(id)) {
            LOG.error("removeDir, dir({}) is not empty", dir);
            return DBResultCode.NOT_EMPTY;
        }
        
        return execute("delete from " + TABLE_DIR + " where id="+id+" and " + SEG_UPDATETIME + '<' + ut);
    }

    //alert: sql only supports `select *`
    private boolean exists(String sql) {
        Object[] l = rdb.queryLine("select exists(" + sql + ')');
        return l != null && l.length > 0 && ValParser.parseInt(l[0], 0) > 0;
    }

    private static final String SQL_DIREXISTS_PREFIX = "select * from " + TABLE_DIR + " where ";
    boolean dirExists(long id) {
        return exists(SQL_DIREXISTS_PREFIX + "id=" + id);
    }
    
    String dirName(long id) {
        String sql = "select name from " + TABLE_DIR + " where id=" + id;
        Object[] line = rdb.queryLine(sql);
        if (line == null || line.length == 0) {
            return null;
        }
        return ValParser.parseString(line[0]);
    }
    
    /**
     * test the item exists or not
     * @param item path to the item, can't be ended by '/'
     * @return true if exists
     */
    public boolean itemExists(String item) {
        int pos = item.lastIndexOf('/');
        String dir = pos == 0 ? ROOT_DIR : item.substring(0, pos);
        String name = item.substring(pos + 1);
        
        long fid = keyId(dir);
        String sql = SQL_ITEMEXISTS_PREFIX + "fid=" + fid + " and name='" + name + "'";
        return exists(sql);
    }

    boolean hasSubDir(long id) {
        return exists(SQL_DIREXISTS_PREFIX + "fid=" + id);
    }

    boolean hasSubItem(long fid) {
        return exists(SQL_ITEMEXISTS_PREFIX + "fid=" + fid);
    }

    /**
     * get lastest update time, to decide which one should be the master
     * @return database update time
     */
    public long lastestUpdate() {
        long t1 = 0;
        long t2 = 0;

        Object[] res = rdb.queryLine("select max(undate_time) from " + TABLE_ITEM);
        if(res != null && res.length > 0) {
            t1 = ValParser.parseLong(res[0], 0);
        }

        res = rdb.queryLine("select max(undate_time) from " + TABLE_DIR);
        if(res != null && res.length > 0) {
            t2 = ValParser.parseLong(res[0], 0);
        }
        
        return Math.max(t1, t2);
    }
    //-------------------------------------------------------------------------
    /**
     * handle request from http request.
     * result is saved into resp
     * @param opr request action
     * @param resp response content
     * @return result
     */
    public DBResultCode handleRequest(Map<String, Object> opr, Map<String, Object> resp) {
        long ut = System.currentTimeMillis();
        String action = ValParser.getAsStr(opr, TREEDB_REQ_ACTION);
        char firstChar = action.charAt(0);
        String key = ValParser.getAsStr(opr, TREEDB_REQ_KEY);

        try {
            if(firstChar == TREEDB_ACTION_G) { //actions start with 'g', 'get' actions
                return handleGet(action, key, opr, resp);
            }
            
            if(firstChar == 'n' || firstChar == 'l') { //names, list
                String as = ValParser.getAsStr(opr, TREEDB_REQ_AS, null);
                if(as == null) {
                    as = getName(key);
                }
                
                if(action.equals(ACTION_NAMES)) {
                    resp.put(as, names(key));
                    return DBResultCode.OK;
                }
                
                if(action.equals(ACTION_LIST)) {
                    List<DirInfo> list = list(key);
                    resp.put(as, list);
                    return DBResultCode.OK;
                }
    
                return DBResultCode.INVALID_ACTION;
            }
    
            if(action.equals(ACTION_ITEMEXISTS)) {
                return itemExists(key) ? DBResultCode.OK : DBResultCode.NOT_EXISTS;
            }

            if(action.equals(ACTION_DIREXISTS)) {
                long id = keyId(key);
                return dirExists(id) ? DBResultCode.OK : DBResultCode.NOT_EXISTS;
            }

            if(!writable()) {
                return DBResultCode.READ_ONLY;
            }
            
            return handleWrite(action, key, opr, ut);
        } catch(Exception e) {
            LOG.error("Fail to execute {} in treedb", opr, e);
            return DBResultCode.UNKOWN_ERROR;
        }
    }
    
    private DBResultCode handleGet(String action, String key,
            Map<String, Object> opr, Map<String, Object> resp) {
        String as = ValParser.getAsStr(opr, TREEDB_REQ_AS, null);
        if(as == null) {
            as = TreeDBWorker.getName(key);
        }
        
        if(action.equals(ACTION_GET)) {
            ItemInfo ii = get(key);
            if(ii == null) {
                String defaultVal = ValParser.getAsStr(opr, TREEDB_REQ_DEFAULT, null);
                if(defaultVal == null) {
                    return DBResultCode.NOT_EXISTS;
                }
                resp.put(as, defaultVal);
                return DBResultCode.OK;
            }
            resp.put(as, ii.val);
            return DBResultCode.OK;
        }
        
        if(action.equals(ACTION_GETS)) {
            List<ItemInfo> iil;
            if(opr.containsKey(TREEDB_REQ_VALUE)) { //指定了最小更新时间
                long from = ValParser.getAsLong(opr, TREEDB_REQ_VALUE);
                iil = gets(key, from);
            } else {
                iil = gets(key);
            }

            resp.put(as, iil);
            return DBResultCode.OK;
        }
        
        if(action.equals(ACTION_GETSUBS)) {
            if(!dirExists(keyId(key))) {
                return DBResultCode.NOT_EXISTS;
            }
            resp.put(as, getSubs(key));
            return DBResultCode.OK;
        }
        
        if(action.equals(ACTION_GETSUBSANDITEMS)) {
            if(!dirExists(keyId(key))) {
                return DBResultCode.NOT_EXISTS;
            }
            resp.put(as, getSubsAndItems(key));
            return DBResultCode.OK;
        }
        
        if(action.equals(ACTION_GETS_MAP)) { //return value as a map
            List<ItemInfo> iil;
            if(opr.containsKey(TREEDB_REQ_VALUE)) { //minimum time identified
                long from = ValParser.getAsLong(opr, TREEDB_REQ_VALUE);
                iil = gets(key, from);
            } else {
                iil = gets(key);
            }

            Map<String, Object> maps = new HashMap<>(iil.size());
            for(ItemInfo ii : iil) {
                Map<String, Object> one = JsonUtil.jsonToMap(ii.val);
                if(one == null) {
                    maps.put(ii.name, new Object());
                } else {
                    maps.put(ii.name, one);
                }
            }
            resp.put(as, maps);
            return DBResultCode.OK;
        }
        
        if(action.equals(ACTION_GET_FROMMAP)) {
            Map<String, Object> val = getMap(key);
            if(val == null) {
                return DBResultCode.NOT_EXISTS;
            }
            String seg = ValParser.getAsStr(opr, TREEDB_REQ_VALUE, null);
            if(StringUtil.isEmpty(seg)) { //name of segment in map not specified, just return the whole map
                resp.put(as, val);
                return DBResultCode.OK;
            }

            Object v = val.get(seg);
            if(v == null) {
                return DBResultCode.NOT_EXISTS;
            }
            //if 'as' not set, use segment name as the result name
            resp.put(opr.containsKey(TREEDB_REQ_AS) ? as : seg, v);

            return DBResultCode.OK;
        }
        
        if(action.equals(ACTION_GET_DIRID)) {
            resp.put(as, TreeDBWorker.keyId(key));
            return DBResultCode.OK;
        }
        
        return DBResultCode.INVALID_ACTION;
    }
    
    private DBResultCode handleWrite(String action, String key, Map<String, Object> opr, long ut) {
        char c = action.charAt(0);
        if(c == TREEDB_ACTION_P) {
            String val = ValParser.getAsStr(opr, TREEDB_REQ_VALUE);
            if(action.equals(ACTION_PUT)) {
                return put(key, val, ut);
            } else if(action.equals(ACTION_PUT_IFABSENT)) {
                return putIfAbsent(key, val, ut);
            } else if(action.equals(ACTION_PUTLIST)) {
                return putList(key, val, ut);
            } else if(action.equals(ACTION_PUTMAP)) { //
                /*
                 * all value transferred as plain text, even when it is a json
                 * so in putMap, it need to be converted into json object
                 */
                Map<String, Object> map = JsonUtil.jsonToMap(val);
                return putMap(key, map, ut);
            } else if(action.equals(ACTION_PUTS)) { //write more than one items under a directory
                Map<String, Object> map = JsonUtil.jsonToMap(val);
                return puts(key, map, ut);
            }
            return DBResultCode.INVALID_ACTION;
        }
        
        if(c == TREEDB_ACTION_R) {
            if(action.equals(ACTION_REMOVE)) {
                return remove(key, ut);
            } else if(action.equals(ACTION_REMOVES)) {
                return removes(key, ut);
            } else if(action.equals(ACTION_REMOVEDIR)) {
                return removeDir(key, ut);
            } else if(action.equals(ACTION_REMOVE_FROMMAP)) {
                String val = ValParser.getAsStr(opr, TREEDB_REQ_VALUE); //key in map
                return removeFromMap(key, val, ut);
            } else if(action.equals(ACTION_REMOVE_FROMLIST)) {
                String val = ValParser.getAsStr(opr, TREEDB_REQ_VALUE);
                return removeFromList(key, val, ut);
            }
        } else if(action.equals(ACTION_CREATEDIR)) {
            return createDir(key, ut);
        }
        
        return DBResultCode.INVALID_ACTION;
    }

    @Override
    public void close() {
        String id = dbId(rdb.cid, rdb.service, rdb.dbName);
        DBWorkers.remove(id);
        try {
            rdb.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Calculate hash code of s, return long, collision ratio is very small, if
     * return integer, when using uuid, collision ratio is 1.5/10000
     * 
     * @param s  can not be ended by '/'
     * @return key id
     */
    public static long keyId(String s) {
        int len = s.length();
        long h = 0;
        char v;
        char fore=0;
        for (int i = 0; i < len; i++) {
            v = s.charAt(i);
            if(v == '/') {
                if (fore == '/') { //ignore consecutive '/'
                    continue;
                }
            }
            h *= 31;
            h += (v & 0xff);
            fore = v;
        }
        return ValParser.absLong(h);
    }
    
    /**
     * get item name from item path
     * @param item item name, must not be ended by '/'
     * @return the last part
     */
    public static String getName(String item) {
        int pos = item.lastIndexOf('/');
        if(pos == 0) {
            return IConst.EMPTY_STR;
        }
        return item.substring(pos + 1);
    }
    
    public static int translateCode(DBResultCode drc) {
        switch(drc) {
        case OK: return RetCode.OK;
        case INSERT_FAILED:
        case NOT_EMPTY:
            return RetCode.DB_ERROR;
        case ALREADY_EXISTS:
            return RetCode.EXISTS;
        case NOT_EXISTS:
        case PARENT_NOT_EXISTS:
            return RetCode.NOT_EXISTS;

        case WRONG_PARA:
        case WRONG_FORMAT:
            return RetCode.WRONG_PARAMETER;
        
        case INVALID_ACTION:
            return RetCode.API_NOT_FOUND;
        case READ_ONLY:
            return RetCode.NO_RIGHT;
        case EXECUTE_FAILED:
            return RetCode.INTERNAL_ERROR;
        default:
            return RetCode.UNKNOWN_ERROR;
        }
    }

    /**
     * Judge whether the result code can be ignored or not.
     * If there is a negative number in ignores, all errors can be ignored
     * @param code the error code
     * @param ignores list of all codes that can't be ignored
     * @return reutrn true if it can be ignored
     */
    public static boolean canIgnore(int code, List<Integer> ignores) {
        if(ignores == null) {
            return false;
        }

        for(int i : ignores) {
            if(i == code || i < 0) {
                return true;
            }
        }
        return false;
    }
    
    public HandleResult handleRequest(AbsServerRequest req, Map<String, Object> resp) {
        Map<String, Object> reqParams = req.params();
        if(ValParser.getAsBool(reqParams, DB_REQ_ISWRITE)) {
            if(!writable()) {
                return new HandleResult(RetCode.NO_RIGHT, "can't write,state=" + this.rdb.state().name());
            }
        }
        
        List<Object> actions = ValParser.getAsList(reqParams, TREEDB_REQ_ACTIONS);
        if(actions == null || actions.isEmpty()) {
            return HandleResult.OK; //do nothing
        }
        Map<String, Object> opr;
        int i = 0;
        int num = actions.size();
        boolean any = ValParser.getAsBool(reqParams, DB_REQ_ANY);
        TreeDBWorker.DBResultCode drc;
        
        try {
            for(i = 0; i < num; i++) {
                opr = ValParser.parseObject(actions.get(i));
                drc = handleRequest(opr, resp);
                if(drc == TreeDBWorker.DBResultCode.OK) {
                    if(any) { //return OK when anyone handled OK
                        break;
                    }
                } else {
                    List<Integer> ignores = ValParser.getAsIntList(opr, TREEDB_REQ_IGNORES, 0);
                    //If any error happened, finish it right now.
                    //But all results generated before will be returned
                    int code = TreeDBWorker.translateCode(drc);
                    if(TreeDBWorker.canIgnore(code, ignores)) {
                        LOG.debug("Fail to execute({}):{},but error({}) is ignored", i, opr, code);
                        continue;
                    }
                    String info = RetCode.getInfo(code);
                    LOG.warn("Fail to execute No.{}:{}, code:{}, info:{}", i, opr, code, info);
                    return new HandleResult(code,  info + ",in action " + i);
                }
            }
            return new HandleResult(RetCode.OK, resp);
        } catch(Exception e) {
            LOG.error("Fail to execute action.{}", i, e);
        }
        return new HandleResult(RetCode.INTERNAL_ERROR, "fail to execute");
    }
}
