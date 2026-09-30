package cn.net.zhijian.mesh.builtin.user;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * grant_type=password&account=xxx&password=yyy&scope=zzz
 * 只支持单端登录
 * @author flyinmind of csdn.net
 *
 */
public final class Login extends UserBase {
    private static final long MIN_RELOGIN_TIME = 3600 * 1000; //S
    private static final int MAX_CONTINUOUS_FAIL_TIMES = 3;
    private static final String SEG_FAILED_LOGIN = "failed_login";
    private static final Logger LOG = LogUtil.getInstance();

    public Login(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        //首先获得密码，然后在此校验密码
        return super.handle(req, resp).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to get user info,result:{}", hr.brief());
                return CompletableFuture.completedFuture(hr);
            }

            String savedPwd = ValParser.getAsStr(hr.data, "pwd");
            if(StringUtil.isEmpty(savedPwd)) {
                LOG.error("Fail to get user info,result:{}", hr.brief());
                return futureResult(RetCode.NOT_EXISTS, "user not found");
            }
            long userId = ValParser.getAsLong(hr.data, "boundTo", -1);
            if(userId < 0) {
                userId = ValParser.getAsLong(hr.data, "id", -1);
            }
            Map<String, Object> params = req.params();
            String pwd = ValParser.getAsStr(params, "password");
            if(!SecureUtil.pbkdf2Check(pwd, savedPwd)) { //判断密码是否正确
                String failedLogin = ValParser.getAsStr(hr.data, SEG_FAILED_LOGIN);
                LOG.warn("Fail to check {}'s password,fail log:{}", req.getString("account"), failedLogin);
                long cur = System.currentTimeMillis();
                if(!StringUtil.isEmpty(failedLogin)) {
                    List<String> ss = StringUtil.split(failedLogin, ',', true);
                    int n = ss.size();
                    long last = Long.parseLong(ss.get(n - 1));
                    if(n >= MAX_CONTINUOUS_FAIL_TIMES) { //连续失败3次，则需要验证时间
                        if(cur - last < MIN_RELOGIN_TIME) {
                            return futureResult(RetCode.FORBIDDEN, "too frequent,retry after an hour");
                        }
                        failedLogin = ss.get(n - 2) + ',' + last;
                    }
                    failedLogin += "," + cur;
                } else {
                    failedLogin = "" + cur;
                }
                //在可重试次数之内，只记录失败时间，记录失败时用到用户id
                return futureResult(MapBuilder.of(SEG_FAILED_LOGIN, failedLogin, "id", userId));
            }

            /* 
             * 如果绑定到其他用户，则使用绑定帐号的用户id；
             * 因为所有UniUser实例的密码本是一样的，所以在哪个节点生成token都可以；
             * 但是token存储的数据库实例必须选择正确的分片对应的库。
             */
            String caller = Long.toString(userId);
            int cid = req.cid();
            return getTokenWorker(cid, req.serviceInfo(), false).thenApplyAsync(tw -> {
                if(tw == null) {
                    return new HandleResult(RetCode.INTERNAL_ERROR, "no tokenworker");
                }
                String account = ValParser.getAsStr(params, "account");
                //生成新token，分区就使用Auth所在的partition
                AccessToken token = tw.create(
                    PartitionConfig.instance().partition,
                    caller,
                    serviceInfo.name, /* 不能写死SERVICE_USER，因为UNIUSER也调用此类实现认证 */
                    System.currentTimeMillis() + USER_TOKEN_EXPIRES_IN,
                    account + AccessToken.EXT_SEPARATOR + cid, //ext(account,cid)
                    TOKENTYPE_USER
                );

                String tokenStr = token.generate();
                
                resp.put(SEG_ACCESS_TOKEN, tokenStr);
                resp.put(SEG_REFRESH_TOKEN, StringUtil.base64UUID());
                resp.put(SEG_EXPIRES_AT, token.expiresAt());//token内部做了调整，不同于输入值
                resp.put(SEG_TOKEN_TYPE, "session");
                resp.put("id", caller); //用在下一步存到token表中
                
                /*
                 * 登录后的token存入数据库，是为了支持REFRESH_TOKEN、防止多端登录。
                 * Login的实例，不一定是Verify的实例，无法更新缓存。
                 * 这时verify实例的缓存中有token，跟再次登录或refresh的token不一致，导致verify失败。
                 * 所以在token有效，但是跟缓存不一致的情况，verify需要从数据库刷新缓存。
                 */
                long tokenHash = StringUtil.longHashCode(tokenStr);
                resp.put(PARA_TOKENHASH, tokenHash); //用在下一步存储token中
                //如果是绑定帐号，且被绑定帐号不属于此实例处理，则此处的缓存没用
                long cacheId = UserBase.getCacheId(cid, tokenStr);
                UserTokenCache.put(cacheId, token);

                return new HandleResult(RetCode.OK, resp);
            }, Pool);
        }, Pool);
    }
}