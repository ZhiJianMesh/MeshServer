package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * 脚本解析成一段一段的，每段可以是普通字符串，也可以是一个占位符。
 * 请求参数、中间步骤的响应参数中都可以加函数，当前支持HASH\MD5\SHA256等函数，
 * 函数参数可以有多个，比如@{ABSHASH|p1,p2,p3}，@[MD5|p1,p2,p3]
 * 参数中可以有多层次，比如@{l.m.n}，多层的性能会差一些，因为要多次从map中获取
 * 与freemarker的性能对比测试结果：
 * 1）实例化以下模板200万次（不包括编译），单线程执行，
 * js：144927/s，freemarker：114678/s，两者相当。
 * var arr=['a','b','c','d','e','f'];
 *   + "var sqls=['insert into t(n,t,u) values'];"
 *   + "for(var i in arr){"
 *   + " if(i>0)sqls.push(',');"
 *   + " sqls.push(`('`,arr[i],`',@{#reqAt},@{#reqAt})`)"
 *   + "}"
 *   + "DB.sql(sqls.join(''))"
 * 2）实例化以下模板200万次，ScriptElement：1710863/s，freemarker：513347/s，
 * ScriptElement是freemarker的3倍以上。
 * \@{a},\@{b},\@{c},\@{arr}
 * <p>
 * freemarker在安卓环境中需要自己建javax.swing.tree.TreeNode等一系列javax的类，
 * 存在不确定的兼容性问题，所以弃用freemarker，改用ScriptElement实现模板解析，
 * 复杂场景使用js配合实现。
 * 
 * @author flyinmind of csdn.net
 */
public abstract class ScriptElement {
    private static final Logger LOG = LogUtil.getInstance();
    
    private static final String FUN_HASH = "HASH";
    private static final String FUN_HASHMOD = "HASHMOD";
    private static final String FUN_ABSHASH = "ABSHASH";
    
    private static final String FUN_NOW = "NOW";
    private static final String FUN_UTC = "UTC";
    private static final String FUN_NEXTPERIOD = "NEXTPERIOD";
    
    private static final String FUN_MD5 = "MD5";
    private static final String FUN_SHA256 = "SHA256";
    private static final String FUN_HMACSHA256 = "HMACSHA256";
    private static final String FUN_PBKDF = "PBKDF";
    private static final String FUN_PBKDFCHECK = "PBKDFCHECK";
    private static final String FUN_CONCAT = "CONCAT";
    private static final String FUN_UUID = "UUID";
    private static final String FUN_SRCIP = "SRCIP";
    private static final String FUN_UNIQUEID = "UNIQUEID";
    private static final String FUN_COUNTER = "COUNTER";
    private static final String FUN_ENCODE = "ENCODE";
    private static final String FUN_DECODE = "DECODE";
    private static final String FUN_ECKEYPAIR = "ECKEYPAIR";
    private static final String FUN_CONFIG = "CONFIG";
    private static final String FUN_SEQUENCE = "SEQUENCE";
    
    private static final String FUN_UPPER = "UPPER";
    private static final String FUN_LOWER = "LOWER";
    private static final String FUN_SPLIT = "SPLIT";
    private static final String FUN_STRPART = "STRPART";
    private static final String FUN_SUBSTR = "SUBSTR";
    private static final String FUN_REPLACE = "REPLACE";
    
    private static final String FUN_COALESCE = "COALESCE";
    private static final String FUN_IFVALID = "IFVALID";
    private static final String FUN_RANDOM = "RANDOM";
    private static final String FUN_JSON = "JSON";
    private static final String FUN_IFNULL = "IFNULL";
    private static final String FUN_LIST = "LIST";
    private static final String FUN_ELEMENT = "ELEMENT";
    private static final String FUN_SUM = "SUM"; //对列表中的值求和
    private static final String FUN_MAX = "MAX";
    private static final String FUN_MIN = "MIN";
    private static final String FUN_SIZE = "SIZE";
    private static final String FUN_CLEAN = "CLEAN";
    private static final String FUN_CLEAR = "CLEAR";
    private static final String FUN_ADD = "ADD";
    private static final String FUN_SUB = "SUB";
    private static final String FUN_MULTI = "MULTI";
    private static final String FUN_DIV = "DIV";
    private static final String FUN_CALCULATE = "CALCULATE"; //算式计算
    private static final String FUN_CONDITION = "CONDITION"; //条件运算
    private static final String FUN_SWITCH = "SWITCH"; //一个或多个条件运算，必须有else
    private static final String FUN_FOR = "FOR"; //对一个数组进行循环
    private static final String FUN_VERCONVERT = "VERCONVERT";
    private static final String FUN_FILE = "FILE";
    private static final String FUN_BASE64IMG = "BASE64IMG";
    private static final String FUN_CONST = "CONST"; //开发语言中的常量
    private static final String FUN_URL = "URL"; //url

    private static final char CONCAT_SEPERATOR = '-';
    public static final String PLACEHOLDER_START = "@{";
    public static final char PLACEHOLDER_END = '}';

    public enum EleType{
        PARA, /* 请求参数、响应参数、系统参数占位符 */
        STR,  /* 单纯的字符串，无需运行时替换 */
        NUM   /* 单纯的数字，无需运行时替换，只在sharding中用到 */
    }

    protected ApiParaHolder[] paras = null;
    protected final EleType type;
    protected String quote; //字符串引号符号
    protected String safeQuote; //将字符串中的引号替换成可安全使用的引号

    /**
     * @param paras 占位符参数
     * @param type 类型，NUM\STR\PARA
     * @param quote 脚本中使用的字符串引号符，比如sql中使用单引号
     * @param safeQuote quote被替换成safeQuote，比如sql中单引号被替换成''
     *  quoto、safeQuote不同的情况下辖默认值不同，比如sql中分别为',''，js中为",\"
     */
    public ScriptElement(String paras, EleType type, String quote, String safeQuote) {
        this.type = type;
        this.quote = quote;
        this.safeQuote = safeQuote;
    }

    public abstract Object run(AbsServerRequest req, Map<String, Object> resp);
    
    @Override
    public String toString() {
        return this.getClass().getSimpleName()
               + "," + this.type.name()
               + "," + this.firstName();
    }
    
    public List<String> segments() {
        List<String> segs = new ArrayList<>();
        if(paras == null) {
            return segs;
        }

        for(ApiParaHolder aph : paras) {
            if(aph.isRequestPara()) {//只判断普通请求参数
                segs.add(aph.name);
            }
        }
        return segs;
    }
    
    protected static String convertQuotes(Object o, String quote, String safeQuote) {
        if(o instanceof String) {
            if(!StringUtil.isEmpty(quote) && safeQuote != null) {
                return ((String)o).replace(quote, safeQuote);
            }
            return (String)o;
        }
        
        if(o instanceof Number) {
            return o.toString();
        }
        
        String s = JsonUtil.objToJson(o);
        if(!StringUtil.isEmpty(quote) && safeQuote != null) {
            return s.replace(quote, safeQuote);
        }
        return s;
    }

    protected String convertQuotes(Object o) {
        return convertQuotes(o, this.quote, this.safeQuote);
    }

    private static final class STRING extends ScriptElement {
        private final String s;

        public STRING(String paras, String quote, String safeQuote) {
            super(paras, EleType.STR, quote, safeQuote);
            this.s = paras;
            this.paras = new ApiParaHolder[]{new ApiParaHolder.StrParaHolder(s)};
        }

        @Override
        public Object run(AbsServerRequest req, Map<String, Object> resp) {
            return this.s;
        }

        @Override
        public String firstName() {
            return this.s;
        }
    }

    public static  ScriptElement stringElement(String paras, String quote, String safeQuote) {
        return new ScriptElement.STRING(paras, quote, safeQuote);
    }
    
    private static final class NUMBER extends ScriptElement {
        private final String v;

        public NUMBER(String paras) {
            super(paras, EleType.NUM, "", null);
            this.v = paras;
            this.paras = new ApiParaHolder[]{new ApiParaHolder.StrParaHolder(v)};
        }

        @Override
        public Object run(AbsServerRequest req, Map<String, Object> resp) {
            return v;
        }
        
        @Override
        public String firstName() {
            return this.v;
        }
    }

    /**
     * 普通的参数，如果头部有！，应该在响应参数中出现，否则必须出现在请求参数中。
     * 响应参数，在启动时无法检查，所以在配置接口时，需保证此响应参数会在某一步骤返回。
     * `@{[!]paraName}`
     * @author flyinmind of csdn.net
     */
    private static final class NORMAL extends ScriptElement {
        /**
         * @param name 参数列表，可以是一个用单引号括起来的字符串
         * @param type STR/NUM/PARA，此处固定为PARA
         * @param quote 脚本中字符串的引号，比如sql中用单引号
         * @param safeQuote quote替换成safeQuote，比如sql中将单引号替换为''
         */
        public NORMAL(String name, EleType type, String quote, String safeQuote) {
            super(name, type, quote, safeQuote);
            this.paras = new ApiParaHolder[]{ApiParaHolder.parse(name)};
        }

        @Override
        public Object run(AbsServerRequest req, Map<String, Object> resp) {
            Object o = paras[0].get(req, resp); //NORMAL只接受一个参数
            return o != null ? convertQuotes(o) : IConst.EMPTY_STR;
        }
    }

    static String concatStr(AbsServerRequest req,
            Map<String, Object> resp, ApiParaHolder[] segs) {
        StringBuilder str = new StringBuilder(4096);
        String v;
        boolean notFirst = false;
        for(ApiParaHolder s : segs) {
            v = s.getAsString(req, resp);
            if(notFirst) {
                str.append(CONCAT_SEPERATOR);
            }
            str.append(v);
            notFirst = true;
        }
        return str.toString();
    }
    
    /**
     * 计算多个参数链接起来后的字符串的长整型hashCode
     * @param req 请求
     * @param resp 前面步骤的响应内容，也可以向其中保存内容
     * @param segs 需要处理的字段
     * @return 长整型hash值
     */
    static long concatLHashCode(AbsServerRequest req,
            Map<String, Object> resp, ApiParaHolder[] segs) {
        long hash = 0;
 
        String v;
        boolean notFirst = false;
        for(ApiParaHolder s : segs) {
            v = s.getAsString(req, resp);
            if(notFirst) {
                hash = 31 * hash + CONCAT_SEPERATOR;
            }
            
            int len = v.length();
            for (int i = 0; i < len; i++) {
                hash = 31 * hash + v.charAt(i);
            }
            notFirst = true;
        }
        return hash;
    }

    //用于日志打印，只取第一个
    public String firstName() {
        if(this.paras[0].isRequestPara()) {
            return this.paras[0].name;
        }
        return '!'+this.paras[0].name;
    }

    public EleType getType() {
        return type;
    }

    private static class Element {
        public final Pattern pattern;
        public final Class<? extends ScriptElement> cls;

        public Element(Pattern pattern, Class<? extends ScriptElement> cls) {
            this.pattern = pattern;
            this.cls = cls;
        }
    }

    //|前面一定要加转义，否则变成了‘或’，因为后面是.+，这样所有字符串都能匹配
    private static final Map<String, Element> EmbededElements = new HashMap<>();
    static {
        String intTypes = "(i,|l,|int,|long,)";
        String numTypes = "(i|l|f|d|int|long|float|double)";
        String allTypes = "(i|l|f|d|s|c|int|long|float|double|string|char)";
        String simpleCalculateReg = "(?i)^" + numTypes + "(.\\d)?(,.+){2}$";
        EmbededElements.put(FUN_ABSHASH, new Element(Pattern.compile("(?i)^" + intTypes + "?.+$"), ABSHASH.class));
        EmbededElements.put(FUN_HASH, new Element(Pattern.compile("(?i)^" + intTypes + "?.+$"), HASH.class));
        EmbededElements.put(FUN_HASHMOD, new Element(Pattern.compile("(?i)^\\d+(,.+)*$"), HASHMOD.class));
        EmbededElements.put(FUN_RANDOM, new Element(Pattern.compile("(?i)^" + allTypes + "(,'?\\s*\\w+'?){0,2}$"), RANDOM.class));
        
        EmbededElements.put(FUN_NOW, new Element(Pattern.compile("(?i)^(.+)?$"), NOW.class));
        EmbededElements.put(FUN_UTC, new Element(Pattern.compile("(?i)^(.+,)(.+)(,.+(,\\s*\\d+)?)?$"), UTC.class));
        EmbededElements.put(FUN_NEXTPERIOD, new Element(Pattern.compile("(?i)^.+,.+$"), NEXTPERIOD.class));
                
        EmbededElements.put(FUN_MD5, new Element(Pattern.compile("(?i)^.+(,.+)*$"), MD5.class));
        EmbededElements.put(FUN_SHA256, new Element(Pattern.compile("(?i)^.+(,.+)*$"), SHA256.class));
        EmbededElements.put(FUN_PBKDF, new Element(Pattern.compile("(?i)^.+(,.+)*$"), PBKDF.class));
        EmbededElements.put(FUN_PBKDFCHECK, new Element(Pattern.compile("(?i)^.+(,.+)*$"), PBKDFCHECK.class));
        EmbededElements.put(FUN_HMACSHA256, new Element(Pattern.compile("(?i)^.+(,.+)*$"), HMACSHA256.class));
        EmbededElements.put(FUN_CONCAT, new Element(Pattern.compile("(?i)^.+(,.+)*$"), CONCAT.class));
        EmbededElements.put(FUN_UUID, new Element(Pattern.compile("(?i)^(16|64)?$"), UUID.class));
        EmbededElements.put(FUN_SRCIP, new Element(Pattern.compile("(?i)^(remote)?$"), SRCIP.class));
        EmbededElements.put(FUN_UNIQUEID, new Element(Pattern.compile("(?i)^(i|int|l|long)?$"), UNIQUEID.class));
        EmbededElements.put(FUN_COUNTER, new Element(Pattern.compile("(?i)^(\\d+|.+)*$"), COUNTER.class));
        EmbededElements.put(FUN_ENCODE, new Element(Pattern.compile("(?i)^.+,\\s*[!^]?\\w+$"), ENCODE.class));
        EmbededElements.put(FUN_DECODE, new Element(Pattern.compile("(?i)^.+,\\s*[!^]?\\w+$"), DECODE.class));
        EmbededElements.put(FUN_ECKEYPAIR, new Element(Pattern.compile("(?i)^.*$"), ECKEYPAIR.class));
        
        EmbededElements.put(FUN_CONFIG, new Element(Pattern.compile("(?i)^\\w+$"), CONFIG.class));
        EmbededElements.put(FUN_IFVALID, new Element(Pattern.compile("(?i)^.+(,.+)+$", Pattern.DOTALL), IFVALID.class));
        EmbededElements.put(FUN_SEQUENCE, new Element(Pattern.compile("(?i)^" + intTypes + "?.+(,\\s*\\d+)?(,\\s*[!^]?\\w+)?$"), SEQUENCE.class));
        EmbededElements.put(FUN_UPPER, new Element(Pattern.compile("(?i)^.+$"), UPPER.class));
        EmbededElements.put(FUN_LOWER, new Element(Pattern.compile("(?i)^.+$"), LOWER.class));
        EmbededElements.put(FUN_SUBSTR, new Element(Pattern.compile("(?i)^[!^]?\\w+,\\s*\\d+(,\\s*\\d+)?$"), SUBSTR.class));
        EmbededElements.put(FUN_SPLIT, new Element(Pattern.compile("(?i)^[!|^]?\\w+,(\\s*\\d+|.+),.+$"), SPLIT.class));
        EmbededElements.put(FUN_STRPART, new Element(Pattern.compile("(?i)^[!|^]?\\w+,.+,\\s*-?\\d+$"), STRPART.class));
        EmbededElements.put(FUN_REPLACE, new Element(Pattern.compile("(?is)^[!|^]?\\w+(,.+){2}$"), REPLACE.class));
        
        EmbededElements.put(FUN_JSON, new Element(Pattern.compile("(?i)^[!^]?\\w+(,.+)?$"), JSON.class));
        EmbededElements.put(FUN_COALESCE, new Element(Pattern.compile("(?i)^[!|^]?\\w+(,.+)+$"), COALESCE.class));
        EmbededElements.put(FUN_IFNULL, new Element(Pattern.compile("(?i)^[!|^]?\\w+,.+(,\\s*obj|num)?$"), IFNULL.class));
        EmbededElements.put(FUN_LIST, new Element(Pattern.compile("(?i)^[!|^]?[^.]+\\.?[^.]+(,\\s*.+)?$"), LIST.class));
        EmbededElements.put(FUN_ELEMENT, new Element(Pattern.compile("(?i)^[!|^]?\\w+,.+$"), ELEMENT.class));
        EmbededElements.put(FUN_SUM, new Element(Pattern.compile("(?i)^" + numTypes + "(.\\d)?,\\s*[!|^]?[^.]+\\.?[^.]+?$"), SUM.class));
        EmbededElements.put(FUN_MAX, new Element(Pattern.compile("(?i)^" + numTypes + ",\\s*[!|^]?[^.]+\\.?[^.]+$"), MAX.class));
        EmbededElements.put(FUN_MIN, new Element(Pattern.compile("(?i)^" + numTypes + ",\\s*[!|^]?[^.]+\\.?[^.]+?$"), MIN.class));
        EmbededElements.put(FUN_SIZE, new Element(Pattern.compile("(?i)^[!|^]?\\w+$"), SIZE.class));

        EmbededElements.put(FUN_CLEAN, new Element(Pattern.compile("(?i)^[!|^]?\\w+$"), CLEAN.class));
        EmbededElements.put(FUN_CLEAR, new Element(Pattern.compile("(?i)^[!|^]?\\w+,.+$", Pattern.DOTALL), CLEAR.class));
        EmbededElements.put(FUN_VERCONVERT, new Element(Pattern.compile("(?i)^[!|^]?\\w+(,\\s*tostr)*$"), VERCONVERT.class));
        EmbededElements.put(FUN_ADD, new Element(Pattern.compile(simpleCalculateReg), ADD.class));
        EmbededElements.put(FUN_SUB, new Element(Pattern.compile(simpleCalculateReg), SUB.class));
        EmbededElements.put(FUN_MULTI, new Element(Pattern.compile(simpleCalculateReg), MULTI.class));
        EmbededElements.put(FUN_DIV, new Element(Pattern.compile(simpleCalculateReg), DIV.class));
        EmbededElements.put(FUN_CALCULATE, new Element(Pattern.compile("(?i)^" + numTypes + "(.\\d)?\\s*(,.+)+$", Pattern.DOTALL), CALCULATE.class));
        EmbededElements.put(FUN_CONDITION, new Element(Pattern.compile("(?i)^(.+)(,.+){2,4}$", Pattern.DOTALL), CONDITION.class));
        EmbededElements.put(FUN_SWITCH, new Element(Pattern.compile("(?i)^(.+)(,.+){3}(,.+)+$", Pattern.DOTALL), SWITCH.class));
        EmbededElements.put(FUN_FOR, new Element(Pattern.compile("(?i)^(.+),\\s*`.+`(,.+)+$", Pattern.DOTALL), FOR.class));
        EmbededElements.put(FUN_FILE, new Element(Pattern.compile("(?i)^.+,.+(,.+,\\s*base64)*$"), FILE.class));
        EmbededElements.put(FUN_BASE64IMG, new Element(Pattern.compile("(?i)^.+,.+(,.+,\\s*base64)*$"), BASE64IMG.class));
        EmbededElements.put(FUN_CONST, new Element(Pattern.compile("(?i)^" + allTypes + ",\\s*(max|min|ver|tzOffset)$"), CONST.class));
        EmbededElements.put(FUN_URL, new Element(Pattern.compile("(?i)^(encode|decode|append)(,.+)+$"), URL.class));
    }
    
    /**
     * 创建一个ScriptElement
     * @param type 如果不是包括在@{}中间，则可能是STR或NUM，否则为PARA
     * @param quote 脚本中使用的引号字符，如果设置了，所有quote会被替换为safeQuote
     * @param safeQuote quote被替换为safeQuote
     * @return ScriptElement
     */
    public static ScriptElement create(String name, EleType type, String quote, String safeQuote) {
        if(type == EleType.STR) {
            return new STRING(name, quote, safeQuote);
        }
        
        if(type == EleType.NUM) {
            return new NUMBER(name);
        }

        String n = name.substring(2, name.length() - 1).trim();
        int pos = n.indexOf('|');
        String fun = (pos > 0 ? n.substring(0, pos) : n).trim();
        Element ee = EmbededElements.get(fun.toUpperCase());
        if(ee != null) {
            String para = pos > 0 ? n.substring(pos + 1).trim() : IConst.EMPTY_STR;
            if(ee.pattern.matcher(para).matches()) {
                try {
                    //public ScriptElement(String paras, EleType type, String quote, String safeQuote)
                    return ee.cls.getConstructor(String.class, EleType.class,
                            String.class, String.class)
                           .newInstance(para.trim(), type, quote, safeQuote);
                } catch (Exception e) {
                    LOG.error("Error happened for embeded function `{}`", name, e);
                    return null;
                }
            } else if(pos > 0) { //now、uuid等内置函数，可以没有参数
                LOG.error("Error happened for embeded function `{}`,invalid params,pattern:{}", name, ee.pattern);
                return null;
            }//如果无参数，则当作普通占位符处理
        }

        return new NORMAL(n, type, quote, safeQuote);
    }
    
    /**
     * 解析脚本中的占位符，并返回一个ScriptElement数组，每个元素中
     * 都记录了占位符的类型，调用run函数，就可以从数据中获得响应的值。
     * 占位符支持函数，当前只支持HASH,MD5,SHA256,参数中可以包括多个字段，用逗号分隔。
     * 占位符的种类：
     * 1)"@{" ：请求参数，如果第一个字符是'!'，则为响应参数，end with '}'
     * 2)"@[" ：前面SQL执行结果，只能用在SQL中，end with ']', 只在webdb中使用
     *
     * @param str 脚本
     * @param availableParas 可获得的系统参数
     * @param quote 字符串引号
     * @param safeQuote 引号替换成为的字符串
     * @param ss 列表，存放解析结果
     * @return 解析后得到的占位符列表
     */
    public static boolean parsePlaceHolder(String str,
            Set<String> availableParas, String quote, String safeQuote,
            List<ScriptElement> ss) {
        String s;
        int i = 0;
        int start = 0;
        int errNo;
        int len = str.length();
        ScriptElement se;

        while(i < len) {
            i = str.indexOf(PLACEHOLDER_START, start); //没有考虑字符串中出现`@{`
            if (i < 0) { //没找到开头，直接结束
                i = len;
                break;
            }

            if (i > start) { //碰到了占位符的开始标识，则先保存前面的字符串
                s = str.substring(start, i); //不可以trim
                ss.add(create(s, EleType.STR, quote, safeQuote));
                start = i;
            }

            //找到了结束符，注意，如果参数中有'}'，必须用``括起来，否则会找到错误的结束位置
            i = StringUtil.indexOf(str, PLACEHOLDER_END, i + 2/*skip over `@{`*/, ApiParaHolder.QUOTATION_MARK);
            if(i < 0) {
                LOG.error("Fail to get the end of {} in `{}`", str.substring(start, start + 5), str);
                return false;
            }

            s = str.substring(start, i + 1);//include start end end
            se = create(s, EleType.PARA, quote, safeQuote);
            if(se == null) {
                LOG.error("Fail to create parameter `{}` in `{}`", s, str);
                return false;
            }
            List<String> paras = se.segments();
            if(paras != null && availableParas != null) { //检查需要的参数是否存在
                if((errNo = checkParas(paras, availableParas)) >= 0) {
                    LOG.error("Can't find the parameter `{}` in `{}`", paras.get(errNo), str);
                    return false;
                }
            }
            ss.add(se);
            start = i + 1;//skip over `}`
        }
        if (i > start) {
            s = str.substring(start, len);
            ss.add(create(s, EleType.STR, quote, safeQuote));
        }
        return true;
    }
    
    public static ScriptElement[] parsePlaceHolder(String str, Set<String> availableParas,
            String quote, String safeQuote) {
        List<ScriptElement> ss = new ArrayList<>();
        if(!parsePlaceHolder(str, availableParas, quote, safeQuote, ss)) {
            return null;
        }
        return ss.toArray(new ScriptElement[0]);
    }
    
    /**
     * 解析多个字符串，用在复杂的占位符中，
     * 比如CALCULATE、FOR、IFVALID、CONDITION、SWITCH等
     * @param holders 字符串数组
     * @param start 开始编号
     * @param end 结束编号
     * @param quote 引号
     * @param safeQuote 引号需要转变成的符合
     * @return script列表
     */
    protected static List<ScriptElement> parseHolders(String[] holders, int start, int end, String quote, String safeQuote) {
        String s;
        int len;
        List<ScriptElement> scrs = new ArrayList<>();
        
        for(int i = start; i < end; i++) {
            s = holders[i];
            len = s.length();
            //字符串中又引用参数，只解析``括起来的部分
            if(s.charAt(0) == ApiParaHolder.QUOTATION_MARK
               && s.charAt(len - 1) == ApiParaHolder.QUOTATION_MARK) {
                if(!parsePlaceHolder(s.substring(1, len - 1), null, quote, safeQuote, scrs)) {
                    LOG.warn("Fail to parse {}", s);
                    return null;
                }
            } else {
                if(s.charAt(0) == '\'' && s.charAt(len - 1) == '\'') {
                    scrs.add(new STRING(s.substring(1, len - 1), quote, safeQuote));
                } else if(s.matches("^[-+]?\\d*(\\.\\d+)?$")) {
                    scrs.add(new NUMBER(holders[i]));
                } else {
                    scrs.add(new NORMAL(s, EleType.PARA, quote, safeQuote));
                }
            }
        }
        return scrs;
    }
    
    protected static List<ApiParaHolder> listReqParameters(List<ScriptElement> scrs) {
        List<ApiParaHolder> holders = new ArrayList<>();
        if(scrs == null || scrs.isEmpty()) {
            return holders;
        }
        for(ScriptElement se : scrs) {
            if(se.paras == null) {
                continue; //比如SEQUENCE、UUID等没有参数
            }
            for(ApiParaHolder aph : se.paras) {
                if(aph.isRequestPara()) {
                    holders.add(aph);
                }
            }
        }
        return holders;
    }
    
    /**
     * 将一段脚本元素，根据请求与响应，转译成字符串
     * @param elements 脚本元素
     * @param req 请求体
     * @param resp 响应内容
     * @return 转译后的字符串
     */
    public static String runAll(ScriptElement[] elements, AbsServerRequest req, Map<String, Object> resp) {
        if(elements == null) {
            return null;
        }
        if(elements.length == 0) { //elements长度为0与为空不同
            return IConst.EMPTY_STR;
        }
        StringBuilder sb = new StringBuilder(4096);
        Object o;
        for(ScriptElement se : elements) {
            if((o = se.run(req, resp)) == null) {
                continue;
            }
            sb.append(o);//不必转换字符串，每个ScriptElement自己转换
        }
        return sb.toString();
    }
    
    public static int checkParas(List<String> paras, Set<String> availableParas) {
        int i = 0;
        for(String p : paras) {
            if(availableParas.contains(p)) {
                i++;
                continue;
            }
            return i;
        }
        return -1;
    }
}