package cn.net.zhijian.mesh.frm.abs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * 数据库工人接口
 * @author flyinmind of csdn.net
 *
 */
public abstract class AbsDBWorker implements IDBConst {
    private static final Logger LOG = LogUtil.getInstance();

    private static final Set<String> WRITE_ACTS = new HashSet<>(Arrays.asList(
            ACTION_PUT, 
            ACTION_PUTS,
            ACTION_PUT_IFABSENT,
            ACTION_PUTLIST,
            ACTION_PUTMAP,
            
            ACTION_REMOVE,
            ACTION_REMOVE_FROMMAP,
            ACTION_REMOVE_FROMLIST,
            ACTION_REMOVES,
            ACTION_REMOVEDIR,
            
            ACTION_CREATEDIR,
            
            ACTION_SCRIPT
    ));
    
    private static final Set<String> READ_ACTS = new HashSet<>(Arrays.asList(
            ACTION_GET,
            ACTION_GETS,
            ACTION_GETS_MAP,
            ACTION_GET_FROMMAP,
            ACTION_GET_DIRID,
            ACTION_GETSUBS,
            ACTION_GETSUBSANDITEMS,
      
            ACTION_LIST,
            ACTION_ITEMEXISTS,
            ACTION_DIREXISTS,
            ACTION_NAMES
    ));

    protected static final char TREEDB_ACTION_G = 'g'; //get
    protected static final char TREEDB_ACTION_P = 'p'; //put
    protected static final char TREEDB_ACTION_R = 'r'; //remove
    protected static final char TREEDB_ACTION_C = 'c'; //create
    protected static final char TREEDB_ACTION_S = 's'; //script
    
    public static final String PLACEHOLDER_START = "@[";
    public static final char PLACEHOLDER_END = ']';
    
    public static boolean isWriteTreeDBAct(String act) {
        return WRITE_ACTS.contains(act);
    }

    public static boolean isValidTreeDBAct(String act) {
        return READ_ACTS.contains(act) || WRITE_ACTS.contains(act);
    }

    /**
     * 通过service、dbname计算数据库的ID
     * 为了不import其他类，此处又实现了一遍StringUtil.concatHashCode
     * @param service 服务名称
     * @param dbName 数据库名称
     * @return 数据库唯一ID
     */
    public static String dbId(int cid, String service, String dbName) {
        return Integer.toString(cid) + '-' + service + '-' + dbName;
    }

    public abstract String type();
    public abstract String name();
    public abstract void close() throws IOException;
    
    /**
     * 数据库服务端解析sql
     * 与{@link cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement}不同，
     * ScriptElement中只解析@{}括起来的内容。此处解析@[]括起来的内容
     * 当一个rdb process中有多条sql，后面的sql依赖前面的返回时，需要在db服务端进行解析。
     * 在应用中应尽量避免此种情况，因为解析替换参数是比较耗费CPU的事情，
     * 所以尽量将解析替换工作放在请前端完成
     * @param sql 脚本，带占位符
     * @return 编译后的脚本
     */
    private static ScriptElement[] parseSqlPlaceHolder(String sql) {
        List<ScriptElement> ss = new ArrayList<>();
        String s;
        int i = 0;
        int start = 0;
        int len = sql.length();
        
        while(i < len) {
            i = sql.indexOf(PLACEHOLDER_START, start);
            if (i < 0) {
                i = len;
                break;
            }

            if (i > start) { //碰到了占位符的开始标识，则先保存前面的字符串
                s = sql.substring(start, i);
                ss.add(ScriptElement.create(s, ScriptElement.EleType.STR, "'", "''"));
                start = i;
            }
            i =  StringUtil.indexOf(sql, PLACEHOLDER_END, i + 2/*skip over "@["*/, ApiParaHolder.QUOTATION_MARK);
            if(i < 0) { //没有找到结束符，格式错误，但是兼容它
                i = len;
                break;
            }
            s = sql.substring(start, i + 1); //包括结束符，所以加1
            ScriptElement se = ScriptElement.create(s, ScriptElement.EleType.PARA, "'", "''");
            ss.add(se);
            start = i + 1;/*skip over "]"*/
        }
        if (i > start) {
            s = sql.substring(start, len);
            ss.add(ScriptElement.create(s, ScriptElement.EleType.STR, "'", "''"));
        }
        return ss.toArray(new ScriptElement[]{});
    }
    
    /**
     * 在webdb服务端编译sql，将前面步骤执行的结果替换到后面步骤的sql中，
     * 用占位符@[!xxx]标识
     * @param sql 脚本
     * @param req 请求
     * @param resp 运行中的数据，请求数据或响应数据……
     * @return 替换了占位符的sql
     */
    public static String compileScript(String sql, AbsServerRequest req, Map<String, Object> resp) {
        ScriptElement[] lSql = parseSqlPlaceHolder(sql);
        StringBuilder sqlStr = new StringBuilder(sql.length() * 2);

        for (ScriptElement se : lSql) {
            Object o = se.run(req, resp);
            if(o == null) {
                LOG.error("Fail to get seg {} from req or resp", se.firstName());
                return null;
            }

            if(o instanceof String) {
                /*
                 * 转义sql字符串中的引号，在se的run中完成，在创建se时，已指定了将'转为''
                 * 如果是字符串，API配置中，必须自己打单引号，且做好转义
                 */
                sqlStr.append((String)o);
            } else if(o.getClass().isPrimitive()) { //String不是primitive
                sqlStr.append(o);
            } else {
                sqlStr.append(JsonUtil.objToJson(o));
            }
        }
        return sqlStr.toString();
    }
    
    /**
     * 将sql中的参数占位符替换成实际的参数
     * 与compileSql不同之处在于，参数占位符是`@{xxx}`
     * 可以使用内置函数，比如HASH。
     * 此函数不会缓存解析后的ScriptElement，而是直接转为字符串
     * @param sql 原始sql
     * @param req 参数，包括cid、service等
     * @param resp 前面步骤的响应内容，也可以向其中保存内容
     * @return 转译后的脚本
     */
    public static String translateSql(String sql, AbsServerRequest req, Map<String, Object> resp) {
        Set<String> availableParas = new HashSet<>();
        ScriptElement[] elements = ScriptElement.parsePlaceHolder(sql, availableParas, "'", "''");
        if(elements == null) {
            LOG.warn("Fail to parse sql `{}`", sql);
            return sql;
        }
        Object val;
        StringBuilder ss = new StringBuilder(sql.length() * 2);
        for (ScriptElement se : elements) {
            if((val = se.run(req, resp)) == null) {
                LOG.error("Can't get val from holder {}", se.firstName());
                return null;
            }
            ss.append(val);
        }
        return ss.toString();
    }
    

    /**
     * 判断是否需要在webdb服务侧中编译，替换掉运行时参数，
     * 此处判断每个ApiParaHolder中是否存在@[...]，
     * 因为大多数sql不需要在webdb中替换运行时参数，所以在启动时就做好判断，减少webdb中判断
     * @param sql 数据库脚本
     * @return 是否需要修改
     */
    public static boolean needCompile(String sql) {
        int pos = sql.indexOf(PLACEHOLDER_START);
        if(pos < 0) {
            return false;
        }
        pos += PLACEHOLDER_START.length();
        //只有找到配对的`]`，才认为是变量占位符
        return sql.indexOf(PLACEHOLDER_END, pos) > pos;
    }
}