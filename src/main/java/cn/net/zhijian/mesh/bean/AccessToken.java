package cn.net.zhijian.mesh.bean;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.IUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * token类型：0-服务间token，1-调用webdb的token，2-用户token
 * 签名类型：0-密码本，1-运维私钥，2-服务私钥
 * @author flyinmind of csdn.net
 * 格式： ver(1) + tokenType(1) + signType(1) + partition(3) + expiresAt(6,seconds)
 *      + @ + caller + @ + callee + @ + extLen(2,max:4096) + ext + sign
 * 如此操作并没有降低安全性，因为不在token中传递，也要在参数中传递
 * 
 */
public final class AccessToken extends DeferrableEle implements IOAuth {
    private static final Logger LOG = LogUtil.getInstance();
    private static final int VERSION_0 = 0;
    public static final int FIXED_HEAD_LEN = 12;
    
    private static final char TOKEN_SEPARATOR = '@';
    public static final char EXT_SEPARATOR = ',';
    public static final String EXT_FEATURE_ALL = "*,";
    
    /**
     * ver:6 | tokenType:6 | signType:6
     * ver最多支持64个，两个type，每个type最多支持64种
     */
    public final int flags;

    /**
     * 分区信息，服务间调用，只能在本分区内；
     * 用户只能调用归属partition的服务，0-2^18
     */
    public final int partition;

    /**
     * 如果是服务端调用中token，则为发起调用的服务名称；
     * 如果是端侧的token，则为用户id
     */
    public final String caller;
    
    /**
     * 1)普通服务的情况，callee是被调方服务名称，不包括前缀；
     * 2)webdb的情况，callee是db名称，不是webdb；
     * 3)用户的情况，caller是用户id，callee是服务名称。
     */
    public final String callee;
    /**
     * 扩展字段，
     * 服务token时，是features，指定调用方可以调用哪些特性；
     * 用户token时，是用户帐号及公司ID
     */
    public final String ext;

    //以下2个成员，通过AccessToken生成对象时，为空，需要通过generate产生
    private String token; //token字符串

    private final ITokenWorker tokenWorker;

    /**
     * 生成token
     * @param tokenWorker token工人
     * @param partition 分区号
     * @param token token原文
     * @param caller 调用方
     * @param callee 被调方
     * @param expiresAt 过期时间,ms
     * @param ext 扩展部分
     * @param flags 标志位
     */
    private AccessToken(ITokenWorker tokenWorker, int partition, String token,
            String caller, String callee, long expiresAt, String ext, int flags) {
        super(0);
        //因为签名时用的是秒，取整是为了防止与签名时不同
        this.expiresAt = (expiresAt / 1000) * 1000;
        this.token = token;
        this.caller = caller;
        this.callee = callee;
        this.partition = partition;
        this.ext = ext;
        this.flags = flags;
        this.tokenWorker = tokenWorker;
    }
    
    public AccessToken(ITokenWorker tokenWorker, int partition, String caller, String callee, long expiresAt,
            String ext, int signType, int tokenType) {
        this(tokenWorker, partition, null, caller, callee, expiresAt,
            ext, buildFlags(VERSION_0, signType, tokenType));
    }
    
    public AccessToken(ITokenWorker tokenWorker, int partition, String caller, String callee,
            String ext, int signType, int tokenType) {
        this(tokenWorker, partition, caller, callee,
             System.currentTimeMillis() + DEFAULT_EXPIRES_IN * 1000,
             ext, signType, tokenType);
    }
    
    /**
     * 格式： ver(1) + tokenType(1) + signType(1) + partition(3) + expiresAt(6)
     *          + @ + caller + @ + callee + @ + extLen(2,max:4096) + ext + sign
     * @param token 令牌
     * @return 调用方名称
     */
    public static String getCaller(String token) {
        int p1 = token.indexOf(TOKEN_SEPARATOR, FIXED_HEAD_LEN);
        if(p1 < FIXED_HEAD_LEN) {
            LOG.debug("Can't find caller's start in token `{}` when get caller", token);
            return IConst.EMPTY_STR;
        }

        p1++;
        int p2 = token.indexOf(TOKEN_SEPARATOR, p1);
        if(p2 <= p1) {
            LOG.debug("Can't find caller's end in token when get caller, p1:{}", p1);
            return IConst.EMPTY_STR;
        }

        return token.substring(p1, p2);
    }

    public static String getCallee(String token) {
        int p1 = token.indexOf(TOKEN_SEPARATOR, FIXED_HEAD_LEN);
        if(p1 < FIXED_HEAD_LEN) {
            LOG.debug("Can't find caller's start in token when get callee");
            return IConst.EMPTY_STR;
        }

        p1++;
        int p2 = token.indexOf(TOKEN_SEPARATOR, p1);
        if(p2 <= p1) {
            LOG.debug("Can't position callee's start in token when get callee, p1:{}", p1);
            return IConst.EMPTY_STR;
        }
        p1 = p2 + 1;
        p2 = token.indexOf(TOKEN_SEPARATOR, p1);
        if(p2 <= p1) {
            LOG.debug("Can't position callee's end in token when get callee, p1:{}", p1);
            return IConst.EMPTY_STR;
        }
        return token.substring(p1, p2);
    }
    
    /**
     * 获得token中的相关双方
     * @param token 令牌
     * @return 调用方与被调方
     */
    public static String getStakeholder(String token) {
        int p1 = token.indexOf(TOKEN_SEPARATOR, FIXED_HEAD_LEN);
        if(p1 < FIXED_HEAD_LEN) {
            LOG.debug("Can't find caller's start in token when get getStakeholder");
            return IConst.EMPTY_STR;
        }

        p1++;
        int p2 = token.indexOf(TOKEN_SEPARATOR, p1);
        if(p2 <= p1) {
            LOG.debug("Can't position callee's start in token when get getStakeholder, p1:{}", p1);
            return IConst.EMPTY_STR;
        }
        p2 = token.indexOf(TOKEN_SEPARATOR, p2 + 1);
        if(p2 <= p1) {
            LOG.debug("Can't position callee's end in token when get getStakeholder, p1:{}", p1);
            return IConst.EMPTY_STR;
        }
        return token.substring(p1, p2);
    }
    
    public static String stakeholder(String caller, String callee) {
        return caller + TOKEN_SEPARATOR + callee;
    }
    
    /**
     * ver(1) + tokenType(1) + signType(1) + partition(3) + expiresAt(6)
     * @param token 令牌
     * @return 令牌类型
     */
    public static int getTokenType(String token) {
        return ByteUtil.getBase64CharVal(token.charAt(1));
    }
    
    /**
     * ver(1) + tokenType(1) + signType(1) + partition(3) + expiresAt(6)
     * @param token 令牌
     * @return 签名类型
     */
    public static int getSignType(String token) {
        return ByteUtil.getBase64CharVal(token.charAt(2));
    }
    
    /**
     * ver(1) + tokenType(1) + signType(1) + partition(3) + expiresAt(6)
     * @param token 令牌
     * @return 分区号
     */
    public static int getPartition(String token) {
        return ByteUtil.base642Int(token, 3, 3); // max 2^18
    }
    
    /**
     * 验证token有效性时调用
     * 格式： ver(1) + tokenType(1) + signType(1) + partition(3) + expiresAt(6)
     *          + @ + caller + @ + callee + @ + extLen(2,max:4096) + ext + sign
     * @param token token字符串
     * @param tw 签名、验签器
     * @return 如果是合法的，则返回对象，否则返回空
     */
    public static AccessToken parse(String token, ITokenWorker tw) {
        int ver = ByteUtil.getBase64CharVal(token.charAt(0));
        if(VERSION_0 != ver) { //当前只有一个版本
            LOG.error("Invalid token version {}, must be {}, token:{}", token.charAt(0), VERSION_0, token);
            return null;
        }
        int p1 = 1;

        int tokenType = ByteUtil.getBase64CharVal(token.charAt(p1));
        if(tokenType > TOKENTYPE_MAX) {
            LOG.error("Invalid token type {}", tokenType);
            return null;
        }
        p1++;
        
        int signType = ByteUtil.getBase64CharVal(token.charAt(p1));
        if(signType > SIGNTYPE_MAX) {
            LOG.error("Invalid token sign type {}", signType);
            return null;
        }
        p1++;
        
        int partition = ByteUtil.base642Int(token, p1, 3);
        if(partition < 0) {
            LOG.error("Invalid token partition {}", partition);
            return null;
        }
        p1 += 3;
        
        long expiresAt = ByteUtil.base642Long(token, p1, 6) * 1000;
        p1 += 6;

        int p2 = token.indexOf(TOKEN_SEPARATOR, p1);
        if(p2 < p1) {
            LOG.error("Invalid token format, there is no '@'");
            return null;
        }

        p1 = p2 + 1;
        p2 = token.indexOf(TOKEN_SEPARATOR, p1);
        if(p2 <= p1 || p2 >= token.length()) {
            LOG.error("Invalid token format, there is no valid caller end '@'");
            return null;
        }
        String caller = token.substring(p1, p2);
        p1 = p2 + 1; //跳过'@'

        p2 = token.indexOf(TOKEN_SEPARATOR, p1);
        if(p2 <= p1 || p2 >= token.length()) {
            LOG.error("Invalid token format, there is no valid callee end '@'");
            return null;
        }
        String callee = token.substring(p1, p2);
        p1 = p2 + 1; //跳过'@'

        int extLen = ByteUtil.base642Int(token, p1, 2);
        p1 += 2;
        if(extLen >= token.length() - p1) {
            LOG.error("Invalid token ext length {}, total length {}", extLen, token.length());
            return null;
        }

        //service_token时，表示特性列表，用逗号分隔；user_token时，表示cid
        p2 = p1 + extLen;
        byte[] extBytes = ByteUtil.base642bin(token.substring(p1, p2));
        String ext = new String(extBytes, IUtil.DEFAULT_CHARSET);
        byte[] sign = ByteUtil.base642bin(token.substring(p2));
        byte[] callerBytes = caller.getBytes(IUtil.DEFAULT_CHARSET);
        byte[] calleeBytes = callee.getBytes(IUtil.DEFAULT_CHARSET);
        int flags = buildFlags(ver, signType, tokenType);

        byte[] signBytes = genSignBytes(flags, partition, expiresAt,
                callerBytes, calleeBytes, extBytes, tw.identifyLen());
        if(!tw.verify(signBytes, sign)) {
            LOG.warn("Invalid token sign from caller({}) to callee({}),tokenType:{},signType:{},{}",
                    caller, callee, TOKENTYPE_NAMES[tokenType],
                    SIGNTYPE_NAMES[signType], StringUtil.shorten(token, 50));
            return null;
        }
        
        AccessToken at = new AccessToken(tw, partition, token, caller, callee, expiresAt, ext, flags);
        if(LOG.isDebugEnabled()){
            LOG.debug("AccessToken:{}", at);
        }

        return at;
    }

    /**
     * 产生待签名的内存
     * @param flags 标志位
     * @param partition 分区号
     * @param expiresAt 过期时间
     * @param caller 调用方
     * @param callee 被调方
     * @param ext 扩展部分
     * @param identifyLen tokenwork标识长度，预先分配相应的长度，用于避免内存拷贝
     * @return 需要签名的内存，末尾会预留identifyLen的长度
     */
    private static byte[] genSignBytes(int flags, int partition, long expiresAt,
            byte[] caller, byte[] callee, byte[] ext, int identifyLen) {
        byte[] signBuf = new byte[4/*flags*/ + 4/*partition*/ + 8/*expiresAt*/
                + caller.length + callee.length + ext.length + identifyLen];
        int pos = 0;

        ByteUtil.encodeInt(signBuf, flags, pos);
        pos += 4;
        ByteUtil.encodeInt(signBuf, partition, pos);
        pos += 4;
        ByteUtil.encodeLong(signBuf, expiresAt, pos);
        pos += 8;
        System.arraycopy(caller, 0, signBuf, pos, caller.length);
        pos += caller.length;
        System.arraycopy(callee, 0, signBuf, pos, callee.length);
        pos += callee.length;
        System.arraycopy(ext, 0, signBuf, pos, ext.length);
        return signBuf;
    }

    /**
     * 密码本签名，只用在oAuth中
     * @return 令牌
     */
    public String generate() {
        if(!StringUtil.isEmpty(this.token)) {
            return this.token;
        }
        /*
         * partition、caller也作为sign的一部分，防止攻击方修改token中的caller，做越权访问。
         * 服务间互调，如果是codebook方式，非user的token，
         * 在verify取缓存的id中包括了caller，已经无法做这种攻击，但是token的签名仍然保持一致
         **/
        byte[] signBuf = genSignBytes(flags, partition, expiresAt,
                    caller.getBytes(IUtil.DEFAULT_CHARSET), callee.getBytes(IUtil.DEFAULT_CHARSET),
                    ext.getBytes(IUtil.DEFAULT_CHARSET), tokenWorker.identifyLen());
        byte[] sign = tokenWorker.sign(signBuf);
        if(sign == null) {
            LOG.error("Fail to sign, signType:{}, tokenType:{}", signType(), tokenType());
            return null;
        }
        this.token = assemble(sign);
        return this.token;
    }

    /**
     * 产生token
     * @param sign 签名
     * @return 令牌
     */
    private String assemble(byte[] sign) {
        byte[] extBytes = ext.getBytes(IUtil.DEFAULT_CHARSET);
        String extBase64 = ByteUtil.bin2base64(extBytes);

        StringBuilder sb = new StringBuilder(4096);
        sb.append(ByteUtil.getBase64CharByVal(VERSION_0))
          .append(ByteUtil.getBase64CharByVal(tokenType()))
          .append(ByteUtil.getBase64CharByVal(signType()))
          .append(ByteUtil.int2Base64(partition, 18, true)) //3字节
          .append(ByteUtil.long2Base64(expiresAt / 1000, 36, true)) //6字节，单位秒，最多到2173年
          .append(TOKEN_SEPARATOR).append(caller)
          .append(TOKEN_SEPARATOR).append(callee)
          .append(TOKEN_SEPARATOR).append(ByteUtil.int2Base64(extBase64.length(), 12, true)) //2字节，最长4096
          .append(extBase64).append(ByteUtil.bin2base64(sign));

        return sb.toString();
    }
    
    public int ver() {
        return (flags >> 12) & 0x3f;
    }

    public int signType() {
        return (flags >> 6) & 0x3f;
    }

    public int tokenType() {
        return tokenType(flags);
    }

    public String tokenTypeName() {
        return TOKENTYPE_NAMES[tokenType(flags)];
    }

    private static int tokenType(int flags) {
        return flags & 0x3f;
    }
    
    private static int buildFlags(int ver, int signType, int tokenType) {
        int flags = (ver & 0x03f);
        flags <<= 6;
        flags |= (signType & 0x03f);
        flags <<= 6;
        flags |= (tokenType & 0x03f);
        return flags;
    }

    /**
     * 计算token的id
     * @return hash值
     */
    public long getHashCode() {
        return hashCode(tokenType(), partition, caller, callee);
    }
    
    /**
     * 计算token唯一标识。因为cid不在sign中，所以减少缓存
     * @param tokenType token类型
     * @param partition 分区号
     * @param caller 对应请求中的clientId
     * @param callee 对应请求中的scope，需要包括db:/srv:/usr:/头部
     * @return hash值
     */
    public static long hashCode(int tokenType, int partition, String caller, String callee) {
        int hash = StringUtil.concatHashCode(
                ByteUtil.int2Base64(partition, 30, true),
                caller,
                SERVICE_SEPERATOR_S);
        if(tokenType == TOKENTYPE_DB) {
            hash = StringUtil.concatHashCode(hash, DB_CALLEE_HEAD);
        } else if(tokenType == TOKENTYPE_USER) {
            hash = StringUtil.concatHashCode(hash, USER_CALLEE_HEAD);
        } else {
            hash = StringUtil.concatHashCode(hash, SERVICE_CALLEE_HEAD);
        }
        return StringUtil.concatHashCode(hash, callee);
    }
    
    /**
     * 格式： ver(1) + tokenType(1) + signType(1) + partition(3) + expiresAt(6) + cid(max 11)
     *       + @ + caller + @ + callee + @ + extLen(2,max:4096) + ext + sign
     * @param token token字符串
     * @return 不计算cid的token hash值
     */
    public static long hashCode(String token) {
        int tokenType = getTokenType(token);
        int partition = getPartition(token);
        //只有user token计算cid，其他类型，不计算cid部分
        int p1 = token.indexOf(TOKEN_SEPARATOR, FIXED_HEAD_LEN);
        if(p1 < FIXED_HEAD_LEN) {
            LOG.warn("Invalid token, can't find caller,tokenType:{}", tokenType);
            return 0L;
        }
        p1++;//跳过@
        int p2 = token.indexOf(TOKEN_SEPARATOR, p1);
        if(p2 < FIXED_HEAD_LEN) {
            LOG.warn("Invalid token, can't find caller, p1:{}", p1);
            return 0L;
        }
        String caller = token.substring(p1, p2);
        p1 = p2 + 1; //跳过@
        p2 = token.indexOf(TOKEN_SEPARATOR, p1);
        if(p2 < FIXED_HEAD_LEN) {
            LOG.warn("Invalid token, can't find caller, p1:{}", p1);
            return 0L;
        }
        String callee = token.substring(p1, p2);

        return hashCode(tokenType, partition, caller, callee);
    }
    
    @Override
    public String toString() {
        String expiresIn = expiresAt > 0 ? (expiresAt - System.currentTimeMillis()) + "ms" : "never";
        return "{partition:" + partition
               + ",caller:" + caller
               + ",callee:" + callee
               + ",tokenType:" + TOKENTYPE_NAMES[tokenType()]
               + ",signType:" + SIGNTYPE_NAMES[signType()]
               + ",ext:" + ext
               + ",expires_in:" + expiresIn
               + '}';
    }
}