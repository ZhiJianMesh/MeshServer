package cn.net.zhijian.mesh.client;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.FifoCache;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 访问config服务的客户端
 * @author flyinmind of csdn.net
 *
 */
public class ConfigClient extends ServiceClient implements IOAuth, IThreadPool {
    private static final Logger LOG = LogUtil.getInstance();

    private static final FifoCache<Integer, String> ConfigCache
        = new FifoCache<>(300, 2000);

    /**
     * 获得配置参数，默认5分钟从config服务刷新一次
     * @param cid 公司id
     * @param caller 调用方
     * @param key 配置项
     * @param useCache 是否用缓存
     * @return 配置项内容
     */
    public static CompletableFuture<String> get(int cid, ServiceInfo caller, String key, boolean useCache) {
        int keyId = cacheId(cid, caller, key);
        if(useCache) {
            String v = ConfigCache.get(keyId);
            if(v != null) {
                return CompletableFuture.completedFuture(v);
            }
        }

        ServiceReqBuilder req = new ServiceReqBuilder(caller, SERVICE_CONFIG)
                .cid(cid)
                .url("/get").appendPara("k", key)
                .appToken("*")
                .traceId(caller.name + '_' + key);
        return getPublic(req).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK || hr.data == null) {
                LOG.error("Fail to get config {}.{},result:{}", cid, key, hr.brief());
                return null;
            }

            String v1 = ValParser.getAsStr(hr.data, key);
            if(useCache) {
                ConfigCache.put(keyId, v1);
            }
            return v1;
        }, Pool);
    }

    /**
     * 创建配置项
     * @param cid 公司id
     * @param caller 调用方信息
     * @param key 配置项名称
     * @param val 配置项值
     * @return 保存结果
     */
    public static CompletableFuture<Boolean> put(int cid, ServiceInfo caller, String key, String val) {
        ServiceReqBuilder req = new ServiceReqBuilder(caller, SERVICE_CONFIG)
                .cid(cid)
                .traceId(caller.name + '_' + key)
                .url("/put")
                .appToken("*")
                .put("k", key)
                .put("v", val);
        return servicePut(req).thenApplyAsync(hr -> {
            if(hr.code == RetCode.OK) {
                int cacheId = cacheId(cid, caller, key);
                ConfigCache.put(cacheId, val);
                return true;
            }
            LOG.error("Fail to call put `{}`, result:{}", req, hr.brief());
            return false;
        }, Pool);
    }

    /**
     * 创建配置项，如果不存在则创建，如果存在，则放弃；返回操作过后的数据库中的值
     * @param cid 公司id
     * @param caller 调用方信息
     * @param key 配置项名称
     * @param val 配置项值
     * @return 保存结果
     */
    public static CompletableFuture<String> putIfAbsent(int cid, ServiceInfo caller, String key, String val) {
        ServiceReqBuilder req = new ServiceReqBuilder(caller, SERVICE_CONFIG)
                .cid(cid)
                .traceId(caller.name + '_' + key)
                .url("/putIfAbsent")
                .appToken("*")
                .put("k", key)
                .put("v", val);
        return servicePost(req).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to call putIfAbsent `{}`, result:{}", req, hr.brief());
                return null;
            }
            String v = ValParser.getAsStr(hr.data, key);
            int cacheId = cacheId(cid, caller, key);
            ConfigCache.put(cacheId, v);
            return v;
        }, IThreadPool.Pool);
    }
    
    /**
     * 删除配置项
     * @param cid 公司id
     * @param caller 调用方信息
     * @param key 配置项名称
     */
    public static void remove(int cid, ServiceInfo caller, String key) {
        ServiceReqBuilder req = new ServiceReqBuilder(caller, SERVICE_CONFIG)
                .cid(cid)
                .traceId(caller.name + '_' + key)
                .url("/remove")
                .appToken("*")
                .put("k", key);
        serviceDelete(req).whenCompleteAsync((hr,e) -> {
            if(e != null) {
                LOG.error("Fail to call remove `{}`", req, e);
            } else if(hr.code != RetCode.OK) {
                LOG.error("Fail to call remove `{}`, result:{}", req, hr.brief());
            } else {
                int cacheId = cacheId(cid, caller, key);
                ConfigCache.remove(cacheId);
            }
        }, Pool);
    }
    
    private static int cacheId(int cid, ServiceInfo caller, String key) {
        return StringUtil.concatHashCode(ByteUtil.int2Hex(cid, false), caller.name, "-", key);
    }
}
