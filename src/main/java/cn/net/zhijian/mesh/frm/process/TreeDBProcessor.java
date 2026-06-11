package cn.net.zhijian.mesh.frm.process;

import java.util.ArrayList;
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
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsDBProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsDBWorker;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.js.JsEngine;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 树状数据库处理器
 * @author flyinmind of csdn.net
 *
 */
public class TreeDBProcessor extends AbsDBProcessor {
    private static final Logger LOG = LogUtil.getInstance();

    private static final String TREEDB_CFG_ACTIONS = "actions";
    private static final String TREEDB_CFG_KEY = "key";
    private static final String TREEDB_CFG_VALUE = "value";
    private static final String TREEDB_CFG_SEG = "seg";
    private static final String TREEDB_CFG_AS = "as";
    private static final String TREEDB_CFG_DEFAULT = "default";
    private static final String TREEDB_CFG_ACTION = "action";
    private static final String TREEDB_CFG_IGNORES = "ignores";

    protected TreeDBStatement[] actions;

    public TreeDBProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> respData) {
        return sendDBRequest(req, TREEDB_API_URL, this.db, respData);
    }
    
    @Override
    protected HandleResult buildRequest(AbsServerRequest req, String db, Map<String, Object> respData, int dbNo) {
        Map<String, Object> reqData = new HashMap<>();
        reqData.put(DB_REQ_DB, db);
        reqData.put(DB_REQ_DBNO, dbNo);
        reqData.put(DB_REQ_ISWRITE, this.hasWrite);
        reqData.put(DB_REQ_TIME, req.reqTime);
        if(!this.hasWrite) {
            if(this.actions.length > 1) {
                reqData.put(DB_REQ_ANY, this.any);
            }
        }
        List<Map<String, Object>> reqList = buildRequestList(req, respData);
        if(reqList == null) {
            LOG.error("Fail to build request list in {}", name());
            return new HandleResult(RetCode.API_ERROR, "fail to build request list");
        }
        if(reqList.isEmpty()) {
            return new HandleResult(RetCode.NO_OPERATION, "empty");
        }
        reqData.put(TREEDB_REQ_ACTIONS, reqList);
        
        return new HandleResult(RetCode.OK, reqData);
    }

    /**
     * 将配置的操作实例化成请求操作，如果是script类型，还会解析成多个操作。
     * @param req 请求
     * @param respData 响应体
     * @return 请求map列表
     */
    protected List<Map<String, Object>> buildRequestList(AbsServerRequest req, Map<String, Object> respData) {
        int no = 0;
        String k;
        String v;
        Map<String, Object> one;
        List<Map<String, Object>> reqList = new ArrayList<>();

        for(TreeDBStatement tdsm : this.actions) {
            no++;
            if(!needRun(req, respData, tdsm.when)) {
                continue; //needn't run, just ignore it
            }

            if(tdsm.act.equals(ACTION_SCRIPT)) {
                k = translateElements(tdsm.key, req, respData);
                if(StringUtil.isEmpty(k)) {
                    LOG.error("Invalid key in {}.{} when compiling req", name(), no);
                    return null;
                }

                v = translateElements(tdsm.val, req, respData);
                if(StringUtil.isEmpty(v)) {
                    LOG.error("Invalid value in {}.{} when compiling req", name(), no);
                    return null;
                }

                List<Map<String, Object>> acts = buildScripts(name(), no, v, k);
                if(acts == null) {
                    LOG.error("Fail to parse script statement `{}`", v);
                    return null;
                }
                reqList.addAll(acts); //必须返回一个数组，可以包含多个操作
                continue;
            }

            if((one = tdsm.build(name(), no, req, respData)) == null) {
                LOG.error("Fail to parse statement @{}", no);
                return null;
            }
            reqList.add(one);
        }

        return reqList;
    }

    /**
     * db:"mesh_meta",
     * actions:[
     *     {"action":"put", "key":"/service/s1/caller/s2", "value":"user,auth"},
     *     {"action":"put", "key":"/service/s1/nodes/ip1:port1", "value":"0.2.1"}
     * ]
     */
    @Override
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        if(!super.parse(url, cfg, request)) {
            return false;
        }
        
        Set<String> availableParas = availableParas(request);
        List<TreeDBStatement> actions = new ArrayList<>();
        Object o = cfg.get(TREEDB_CFG_ACTIONS);
        if(o instanceof List<?>) {
            List<Object> acts = ValParser.parseList(o);
            for(Object a : acts) {
                TreeDBStatement sm = TreeDBStatement.parse(ValParser.parseObject(a), availableParas);
                if(sm == null) {
                    LOG.error("Fail to parse list `{}` to TreeDBStatements under {}", acts, url.toString());
                    return false;
                }
                if(sm.isWrite()) {
                    this.hasWrite = true;
                }
                actions.add(sm);
            }
        } else { //只有一个action，可以配置成map
            TreeDBStatement sm = TreeDBStatement.parse(ValParser.parseObject(o), availableParas);
            if(sm == null) {
                LOG.error("Fail to parse map `{}` to TreeDBStatement under {}", o, url.toString());
                return false;
            }
            this.hasWrite = sm.isWrite();
            actions.add(sm);
        }

        if(!this.hasWrite) { //没有写操作时，才可以用从库
            this.readSlave = ValParser.getAsBool(cfg, CFG_READSLAVE, false);
        }
        
        if(!actions.isEmpty()) {
            this.actions = actions.toArray(new TreeDBStatement[0]);
            return true;
        }
        
        return false;
    }

    /**
     * 将js脚本翻译成一个或多个treedb操作
     * @param name 处理的名称
     * @param i 编号，第几个action
     * @param script 用'[]'包裹的字符串，或一段js脚本，脚本执行后返回用'[]'包裹的字符串
     * @param parentKey path to parent key of the actions
     * @return operations
     */
    private static List<Map<String, Object>> buildScripts(String name, int i, String script, String parentKey) {
        List<Object> oprs;
        if(script.charAt(0) == '[' && script.charAt(script.length() - 1) == ']') { //脚本直接返回列表，可以不用js开头
            oprs = JsonUtil.jsonToList(script); //远比js脚本快
        } else {
            String pureScript;
            if(script.startsWith(JS_HEAD)) { //因为已知是script，js:头部可以省略
                pureScript = script.substring(JS_HEAD.length());
            } else {
                pureScript = script;
            }
            String json = JsEngine.getString(pureScript);
            if(StringUtil.isEmpty(json) || (oprs = JsonUtil.jsonToList(json)) == null) { //不能为空，但是长度可以为0
                LOG.error("Not a valid response {}\nfrom script `{}`", json, pureScript);
                return null;
            }
            if(LOG.isDebugEnabled()) {
                LOG.debug("script `{}`\nresult:`{}`", StringUtil.blur(pureScript, 50, 3, 100, '.'), json);
            }
        }

        Map<String, Object> one;
        List<Map<String, Object>> statements = new ArrayList<>();
        String key;
        int idx = i * 1000;

        for(Object o : oprs) {
            idx++;
            Map<String, Object> opr = ValParser.parseObject(o);
            if(opr == null
               || (key = ValParser.getAsStr(opr, TREEDB_REQ_KEY)) == null
               || !key.startsWith(parentKey)) {
                LOG.error("Not a valid script operation `{}`, parent key:`{}` @{}.{}", o, parentKey, name, idx);
                return null;
            }

            if((one = TreeDBStatement.parse(opr)) == null) {
                LOG.error("Fail to parse script operation {}@{}.{}", opr, name, idx);
                return null;
            }
            statements.add(one);
        }
        return statements;
    }

    /**
     * operation key value
     * key与value中的内容，如果出现空格与tab，使用\s、\t替代
     * @author flyinmind of csdn.net
     */
    protected static final class TreeDBStatement {
        public final String act;
        public final ScriptElement[] key;
        /**
         *  除了put，与getFromMap，其他操作val为空，
         *  puts、putMap时，val是一个map对象，但是仍然转成字符串处理
         *  在服务侧会将字符串再转成map
         */
        public final ScriptElement[] val;
        public final String as; //get后的别名
        public final String defaultVal; //get不存在时的默认值
        public final List<Integer> ignores;
        public final ScriptElement[] when;

        private TreeDBStatement(String act, ScriptElement[] key,
                ScriptElement[] val, String as, List<Integer> ignores,
                ScriptElement[] when, String defaultVal) {
            this.act = act;
            this.key = key;
            this.val = val;
            this.as = as;
            this.ignores = ignores;
            this.when = when;
            this.defaultVal = defaultVal;
        }

        public boolean isWrite() {
            //写操作:putXXX、rmvXXX、crtXXX
            return AbsDBWorker.isWriteTreeDBAct(act);
        }

        public static TreeDBStatement parse(Map<String, Object> actCfg, Set<String> availableParas) {
            if(actCfg == null || actCfg.isEmpty()) {
                LOG.error("Invalid action, it's empty");
                return null;
            }

            String act = ValParser.getAsStr(actCfg, TREEDB_CFG_ACTION);
            if(!AbsDBWorker.isValidTreeDBAct(act)) {
                LOG.error("Invalid action {}", act);
                return null;
            }

            String s = ValParser.getAsStr(actCfg, TREEDB_CFG_KEY);
            if(StringUtil.isEmpty(s)) {
                LOG.error("Invalid empty key, can't be null in {}", act);
                return null;
            }

            ScriptElement[] key = ScriptElement.parsePlaceHolder(s.trim(), availableParas, IConst.EMPTY_STR, null);
            if(key == null || key.length == 0) {
                LOG.error("Fail to parse TreeDBStatement.key {}", s);
                return null;
            }

            ScriptElement[] when = null;
            s = ValParser.getAsStr(actCfg, CFG_WHEN).trim();
            if(!StringUtil.isEmpty(s)) { //不判断，则可以不指定，是一段逻辑运算，返回true或false
                String formula = s.replace("&&", "*").replace("||", "+"); //变成数学算式
                when = ScriptElement.parsePlaceHolder(formula, availableParas, IConst.EMPTY_STR, null);
                if(when == null || when.length == 0) {
                    LOG.error("Fail to parse {}", CFG_WHEN);
                    return null;
                }
            }
            
            ScriptElement[] val = null;
            Object o;
            if(act.equals(ACTION_GET_FROMMAP)) {
                o = actCfg.get(TREEDB_CFG_SEG); //act:'getMap', seg:'segName'
            } else {
                o = actCfg.get(TREEDB_CFG_VALUE);
            }

            if(o != null) {
                if(o instanceof Map) {
                    s = JsonUtil.objToJson(o);
                } else {
                    s = ValParser.parseString(o);
                }
                val = ScriptElement.parsePlaceHolder(s.trim(), availableParas, IConst.EMPTY_STR, null);
                if(val == null) {
                    LOG.error("Fail to parse TreeDBStatement.val {}", s);
                    return null;
                }
            }
            
            String as = ValParser.getAsStr(actCfg, TREEDB_CFG_AS, null);
            String defaultVal = ValParser.getAsStr(actCfg, TREEDB_CFG_DEFAULT, null);
            List<Integer> ignores = RetCode.parseCodes(ValParser.getAsList(actCfg, TREEDB_CFG_IGNORES));
            return new TreeDBStatement(act, key, val, as, ignores, when, defaultVal);
        }

        /**
         * 将配置的action解析成请求map
         * @param actCfg 配置map
         * @return 请求map
         */
        public static Map<String, Object> parse(Map<String, Object> actCfg) {
            if(actCfg == null || actCfg.isEmpty()) {
                LOG.error("Invalid action, it's empty");
                return null;
            }

            Map<String, Object> action = new HashMap<>();
            String act = ValParser.getAsStr(actCfg, TREEDB_CFG_ACTION);
            if(!AbsDBWorker.isValidTreeDBAct(act)) {
                LOG.error("Invalid action {}", act);
                return null;
            }
            action.put(TREEDB_REQ_ACTION, act);

            String key = ValParser.getAsStr(actCfg, TREEDB_CFG_KEY);
            if(StringUtil.isEmpty(key)) {
                LOG.error("Invalid key, can't be null in {}", act);
                return null;
            }
            action.put(TREEDB_REQ_KEY, key);

            Object o;
            if(act.equals(ACTION_GET_FROMMAP)) {
                o = actCfg.get(TREEDB_CFG_SEG); //act:'getMap', seg:'segName'
            } else {
                o = actCfg.get(TREEDB_CFG_VALUE);
            }
            if(o != null) {
                String val;
                if(o instanceof Map) {
                    val = JsonUtil.objToJson(o);
                } else {
                    val = ValParser.parseString(o);
                }
                action.put(TREEDB_REQ_VALUE, val);
            }
            String as = ValParser.getAsStr(actCfg, TREEDB_CFG_AS, null);
            if(!StringUtil.isEmpty(as)) {
                action.put(TREEDB_REQ_AS, as);
            }

            List<Integer> ignores = RetCode.parseCodes(ValParser.getAsList(actCfg, TREEDB_CFG_IGNORES));
            if(ignores != null) {
                action.put(TREEDB_REQ_IGNORES, ignores);
            }
            return action;
        }

        /**
         * 将一个statement转为请求的map
         * @param name 处理的名称
         * @param i 编号，第几个action
         * @param req 请求
         * @param respData 响应体
         * @return 请求map
         */
        public Map<String, Object> build(String name, int i, AbsServerRequest req, Map<String, Object> respData) {
            String s;
            Map<String, Object> one = new HashMap<>();
            one.put(TREEDB_REQ_ACTION, this.act);
            if((s = translateElements(this.key, req, respData)) == null) {
                LOG.error("Invalid key in {}.{} when building req", name, i);
                return null;
            }

            one.put(TREEDB_REQ_KEY, s);
            if(this.val != null) { //可以没有value
                if((s = translateElements(this.val, req, respData)) == null) {
                    LOG.error("Invalid value in {}.{} when compiling req", name, i);
                    return null;
                }

                /*
                 * treedb的处理中使用
                 * replace into item(fid,name,val) values(?,?,?)
                 * 所以在调用它是，无需对value部分替换单引号
                 */
                one.put(TREEDB_REQ_VALUE, s);
            }

            if(this.as != null) {
                one.put(TREEDB_REQ_AS, this.as);
            }
            
            if(this.defaultVal != null) {
                one.put(TREEDB_REQ_DEFAULT, this.defaultVal);
            }

            if(this.ignores != null) {
                one.put(TREEDB_REQ_IGNORES, this.ignores);
            }
            return one;
        }
    }
}