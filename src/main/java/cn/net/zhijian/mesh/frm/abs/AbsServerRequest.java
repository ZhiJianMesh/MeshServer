package cn.net.zhijian.mesh.frm.abs;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.RequestStat;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.QueryStringDecoder;

public abstract class AbsServerRequest {
    private static final String URL_API = "/api/";
    private static final char TRACEID_SEPERATOR = '#';
    public enum RequestNetType {NONE/*未定义*/, LAN/*局域网*/, WAN/*外网*/} //接入类型

    private static final Logger LOG = LogUtil.getInstance();
    protected static final AtomicInteger Sequence = new AtomicInteger(0);
    private static final RequestStat DefaultRs = new RequestStat(); //默认的统计，只计数，避免为空判断
    
    private final Map<String, Object> params;
    protected final Map<String, String> headers;
    protected final byte[] body;
    
    public final String uri; //uri不包括scheme&参数，url包括uri及scheme&参数
    public final long reqTime;
    public final String traceId; //链路跟踪ID
    public final int depth;
    public final String method;
    public final String protocol;
    public final boolean isApi;
    protected final ChannelHandlerContext ctx;
    private int cid = -1;

    protected AccessToken token = null;
    protected ServiceInfo si; //被调服务的服务信息
    protected RequestNetType reqNetType = RequestNetType.NONE; //是否来自外网访问
    
    public abstract AbsServerResponse createResponse();
    /**
     * 复制除了params外其他所有内存
     * @param params 请求参数
     * @return 请求体
     */
    public abstract AbsServerRequest copy(Map<String, Object> params);
    public abstract String remoteAddr(); //远端地址，不带端口号
    
    protected AbsServerRequest(ChannelHandlerContext ctx,
            String method, String uri, String protocol,
            Map<String, String> headers, Map<String, Object> params,
            byte[] body, String traceId, int depth) {
        this.ctx = ctx;
        this.reqTime = System.currentTimeMillis();
        this.params = params;
        this.uri = parseUrl(uri, this.params);
        this.headers = headers;
        this.method = method;
        this.protocol = protocol;
        this.body = body;
        this.traceId = traceId;
        this.depth = depth;
        this.isApi = uri.contains(URL_API); //api路径是单独分开的，其中必须包括/api/
        this.params.put(IConst.EMBEDED_REQUESTAT, this.reqTime);
    }

    public AbsServerRequest(ChannelHandlerContext ctx,
            String method, String uri, String protocol,
            Map<String, String> headers, Map<String, Object> params,
            byte[] body) {
        this.ctx = ctx;
        this.reqTime = System.currentTimeMillis();
        this.params = params;
        this.uri = parseUrl(uri, this.params);
        this.headers = headers;
        this.method = method;
        this.protocol = protocol;
        this.body = body;
        this.isApi = uri.contains(URL_API);

        int depth = 1;
        String traceId = headers.get(IConst.HEAD_TRACE_ID);
        if(StringUtil.isEmpty(traceId)) { //没有调用链
            this.traceId = Integer.toHexString(Sequence.incrementAndGet());
        } else {
            int pos = traceNoPos(traceId);
            if(pos > 0) { //每次调用下一个服务，id都要增加1
                pos++;
                depth = Integer.parseInt(traceId.substring(pos)) + 1;
                this.traceId = traceId.substring(0, pos) + depth;
            } else {
                this.traceId = traceId + TRACEID_SEPERATOR + "0";
            }
        }
        this.depth = depth;
        this.params.put(IConst.EMBEDED_REQUESTAT, this.reqTime);
        
        if(LOG.isDebugEnabled() && isApi) {
            LOG.debug("REQUEST({},{})=={},Url:{},cid:{}",
                this.traceId, protocol, method, uri, headers.get(IConst.HEAD_CID));
        }
    }

    public boolean parseJsonBody() {
        if(body == null || body.length == 0) {
            return true;
        }
        Map<String, Object> reqData;
        try {
            reqData = JsonUtil.rawJsonToMap(body);
            if(reqData == null) {
                LOG.warn("Invalid request json body `{}`", new String(body));
                return false;
            }
        } catch(Exception e) {
            if(LOG.isDebugEnabled()) { //不用error或warn，防止日志攻击
                LOG.debug("Invalid json body:{}", new String(body), e);
            }
            return false;
        }

        this.params.putAll(reqData);
        if(LOG.isDebugEnabled()) { //请求体在业务线程解析，所以AbsServerRequest生成时params是空
            LOG.debug("Url:{},traceId:{},params({})", uri, traceId, StringUtil.mapToStr(params, 23));
        }
        return true;
    }
    
    static int traceNoPos(String s) {
        char c;
        int end = s.length() - 1;
        for(int i = end; i >= 0; i--) {
            c = s.charAt(i);
            if(!Character.isDigit(c)) {
                return c == TRACEID_SEPERATOR && i < end ? i : -1;
            }
        }
        return -1;
    }

    public Map<String, Object> params() {
        return this.params;
    }

    /**
     * 所有header的名称全部为小写
     * @return 请求头
     */
    public Map<String, String> headers() {
        return this.headers;
    }
    
    public byte[] body() {
        return this.body;
    }

    public String header(String n) {
        return headers.get(n);
    }

    /**
     * 设置token适配器，当请求执行到token认证通过之后，调用此函数
     * @param token token适配器
     */
    public void setToken(AccessToken token) {
        this.token = token;
    }

    /**
     * 设置服务信息，当请求执行到正确获取ApiMethod之后
     * @param si 服务信息
     */
    public void setServiceInfo(ServiceInfo si) {
        this.si = si;
        if(si != null) {
            this.params.put(IConst.EMBEDED_SERVICE, si.name);
        }
    }

    /**
     * @return 被调服务的服务信息
     */
    public ServiceInfo serviceInfo() {
        return si;
    }

    public AccessToken token() {
        return token;
    }

    /**
     * 返回公司id，在需要区分公司的业务中，将cid传递给webdb，
     * webdb在组成db名称时，格式为db+'#'+cid。
     * 而webdb中不可以使用此函数，webdb是common服务，一定返回0，而访问webdb是一定需要token的，
     * 所以直接从token中获取，这需要调用方在服务token中加入调用方的cid。
     * @return 如果有token则从token中获取，没有token则从参数中获取，否则返回0(公共)
     */
    public int cid() {
        //必须有cid头，否则返回-1
        //如果在区分公司的服务中，返回cid为-1，会导致找不到数据库
        if(this.cid > IConst.LOCAL_COMPANY_ID) {
            return this.cid;
        }

        //用户token时，使用token中的cid
        //token解析时，已将ext解开，并存到参数列表params中
        if(token != null && token.tokenType() == IOAuth.TOKENTYPE_USER) {
            this.cid = ValParser.getAsInt(params, IConst.EMBEDED_TOKEN_CID, -1);
            return this.cid;
        }
        //不区分公司的接口，使用头部cid，没有时，默认返回-1
        this.cid = ValParser.parseInt(headers.get(IConst.HEAD_CID), -1);
        return this.cid;
    }
    
    public Object get(String name) {
        return params.get(name);
    }
    
    public AbsServerRequest put(String name, Object v) {
        this.params.put(name, v);
        return this;
    }
    
    public AbsServerRequest putAll(Map<String, Object> params) {
        this.params.putAll(params);
        return this;
    }
    
    public String getString(String name) {
        return ValParser.getAsStr(params, name);
    }

    public int getInt(String name, int def) {
        return ValParser.getAsInt(params, name, def);
    }
    
    public int getInt(String name) {
        return ValParser.getAsInt(params, name, 0);
    }

    public boolean getBool(String name) {
        return ValParser.getAsBool(params, name);
    }
    
    public boolean getBool(String name, boolean defVal) {
        return ValParser.getAsBool(params, name, defVal);
    }
    
    /**
     * 链路中，上一跳的IP，没有考虑NAT转换
     * @return 请求地址
     */
    public String requestAddr() {
        if(ctx != null) {
            //上一跳源IP，没有考虑NAT转换
            InetSocketAddress insocket = (InetSocketAddress)ctx.channel().remoteAddress();
            return insocket.getAddress().getHostAddress();
        }
        return "0.0.0.0";
    }

    public boolean isExternal() {
        return this.reqNetType == RequestNetType.WAN;
    }

    private static String parseUrl(String uri, Map<String, Object> params) {
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(uri);
        Map<String, List<String>> p = queryStringDecoder.parameters();
        if (p != null && !p.isEmpty()) {
            for(Map.Entry<String, List<String>> i : p.entrySet()) {
                List<String> vals = i.getValue();
                if(vals.size() == 1) {
                    params.put(i.getKey(), vals.get(0));
                } else {
                    params.put(i.getKey(), vals);
                }
            }
        }
        return queryStringDecoder.rawPath().toLowerCase();
    }    
    
    /**
     * 返回公司对应的数据库编号
     * 在公有云一个webdb实例会服务多个公司，通过dbNo区分，
     * 在私有云，dbNo固定为LOCAL_DBNO
     * @return 数据库编号
     */
    public CompletableFuture<Integer> getDbNo() {
        int cid = cid();
        return si.getDbNo(cid);
    }
    
    /**
     * 公司在当前服务中的统计信息
     * @return 一个非空的RequestStat对象
     */
    public RequestStat getStat() {
        int cid = cid();
        if(cid <= IConst.LOCAL_COMPANY_ID) { //调用方不必判空，如果公司都是无效的，统计后就丢弃
            return DefaultRs;
        }

        RequestStat rs = si.getStat(cid);
        if(rs != null) {
            return rs;
        }
        
        /*
         * 公司服务，必须曾经获取过dbNo，否则不统计。
         * 因为此处可能会招致攻击，通过不停地发起携带不同cid的请求，导致内存沾满。
         * 如果DbNos是存在的，说明肯定是注册过的公司，且在公有云使用公司级服务。
         * 这样的实现，会导致在调用getDbNo前的请求都无法被统计，
         * 比如一直调用非数据库相关的操作，则一直不会调用getDbNo，导致不统计
         */
        RequestStat newRs = new RequestStat();
        getDbNo().whenCompleteAsync((dbNo, e) -> {
            if(e != null) {
                LOG.error("Fail to get dbNo for company {}", cid, e);
                return;
            }
            if(dbNo >= 0) { //确认公司服务运行在云上才会统计
                si.addStat(cid, newRs);
            }
        }, IThreadPool.Pool);
        return newRs;
    }
}
