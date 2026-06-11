package cn.net.zhijian.mesh.frm.config.aclchecker;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.HttpClient;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.util.FifoCache;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

class RBAC extends AclChecker {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String PARA_UID = "uid"; //用户id
    private static final int USER_F_EXPIRES_IN = 30 * 60; //半小时
    
    /**
     * hashCode(服务名,role,cls) -> 授权的特性列表
     * 通过调用服务的roles接口获得。因为角色权限设置不会经常变动，
     * 所以每小时从rbac服务刷新一次，如果刷新失败，会继续使用老数据
     */
    private static final Map<Long, FeatureRight> roleRights = new HashMap<>();

    static final AclChecker Instance = new RBAC(CHECKER_RBAC);
    /**
     * hashCode(服务名,grpId,userId) -> authorized features
     * 从member中表加载。
     * 存放用户在某个服务、某个群组中的角色，每天需要从服务端刷新一次。
     * 为了减少缓存占用量，在大用户的情况下，最好按用户id进行分发，
     * 将相同用户的请求发送到同一个实例
     */
    private static final FifoCache<Long, String> userRoleCache = new FifoCache<>(
            USER_F_EXPIRES_IN,
            10000); //单实例保存1万个活跃成员，大约需要1M内存;

    RBAC(String name) {
        super(name);
    }
    /**
     * 如果是一个攻击性质的用户，会导致反复的查询rbac
     * 每次都无法获取权限，会导致系统繁忙。
     * 如果通过缓存来记住失败的访问，则有可能会耗费太多内存。
     * 不考虑多线程并发同时获取的情况，这种并发不影响逻辑，只影响性能。
     */
    @Override
    public CompletableFuture<HandleResult> check(ApiInfo ai,
            AbsServerRequest req, Map<String, Object> respData){
        AccessToken token = req.token();
        String userId;
        int cid = req.cid();
        String fromService = req.serviceInfo().name;
        Map<String, Object> params = req.params();
        if (token.tokenType() == TOKENTYPE_USER) {
            userId = token.caller; //用户token的情况，caller是用户id
        } else {//否则要在请求参数中传递
            userId = ValParser.getAsStr(params, PARA_UID);
        }

        long hc = StringUtil.longHashCode(fromService, userId);
        String roleInCache = userRoleCache.get(hc);
        CompletableFuture<String> cf;

        if (StringUtil.isEmpty(roleInCache)) {
            HttpClient.ServiceReqBuilder builder = new HttpClient.ServiceReqBuilder(req.serviceInfo(), SERVICE_USER)
                    .url("/power/getrole?service=" + fromService)
                    .traceId(req.traceId)
                    .cid(cid)
                    .token(req.token().generate());
            cf = ServiceClient.getPublic(builder).thenComposeAsync((hr) -> {
                if (hr.code != RetCode.OK || hr.data == null) {
                    return CompletableFuture.completedFuture(null);
                }
                String role = ValParser.getAsStr(hr.data, "role");
                userRoleCache.put(hc, role);
                return CompletableFuture.completedFuture(role);
            }, Pool);
        } else {
            cf = CompletableFuture.completedFuture(roleInCache);
        }

        return cf.thenComposeAsync((role) -> {
            if (StringUtil.isEmpty(role)) {
                return HandleResult.future(RetCode.NO_RIGHT);
            }
            return checkRight(req, ai, role);
        }, Pool);
    }

    private CompletableFuture<HandleResult> checkRight(AbsServerRequest req, ApiInfo ai, String role){
        String fromService = req.serviceInfo().name;
        long hashCode = StringUtil.longHashCode(fromService, role, ai.cls/*接口配置文件名*/);
        FeatureRight frIncache = roleRights.get(hashCode);
        if (frIncache != null) {
            if (frIncache.hasRight(ai)) {
                return HandleResult.future();
            }
        }

        int cid = req.cid();
        HttpClient.ServiceReqBuilder builder = new HttpClient.ServiceReqBuilder(req.serviceInfo(), fromService)
                .url("roles").traceId(req.traceId).cid(cid);
        //获取所有角色的权限定义
        return ServiceClient.getPublic(builder).thenComposeAsync((hr) -> {
            if (hr.code != RetCode.OK || hr.data == null) {
                return CompletableFuture.completedFuture(hr);
            }

            FeatureRight fr;
            /*
             * 一次查询一个服务的所有角色权限列表，免得多次请求。
             * 全部获取后，需要检查的权限仍然可能不在其中，这时只能返回无权访问。
             */
            for (Map.Entry<String, Object> o : hr.data.entrySet()) {
                String roleName = o.getKey(); //角色名
                Map<String, Object> rightsDef = ValParser.parseObject(o.getValue());
                if (rightsDef == null) {
                    LOG.warn("Invalid roles config in service {} for role {}", fromService, roleName);
                    continue;
                }

                Map<String, Object> rights = ValParser.getAsObject(rightsDef, "rights");
                if (rights == null) {
                    LOG.debug("No rights defined for roles config in service {} for role {}", fromService, roleName);
                    rights = new HashMap<>();
                }

                for (Map.Entry<String, Object> i : rights.entrySet()) {
                    long hc = StringUtil.longHashCode(fromService, roleName, i.getKey()/*api class*/);
                    fr = new FeatureRight(ValParser.parseString(i.getValue())/*allowed features*/);
                    roleRights.put(hc, fr);
                }
            }
            fr = roleRights.get(hashCode);
            if (fr != null && fr.hasRight(ai)) {
                return HandleResult.future();
            }
            return HandleResult.future(RetCode.NO_RIGHT, "invalid role");
        }, Pool);
    }
    
    @Override
    public void destroy() {
        userRoleCache.clear();
        roleRights.clear();
    }
}
