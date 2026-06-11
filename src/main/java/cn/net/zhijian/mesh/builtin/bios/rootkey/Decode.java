package cn.net.zhijian.mesh.builtin.bios.rootkey;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.RootKeystore;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;

/**
 * 服务器中使用公有云加密服务作为根密钥，
 * 安卓中使用KeyStore作为根密钥，
 * 只适合加密可以更改的内容，因为服务器rootkey可能会丢失，比如换手机、服务器或rootkey文件损坏
 * @see <a href="https://www.jianshu.com/p/65b2aa6ec85f">KeyStore简介</a>
 * 其他环境中，原样返回
 * 
 * @author flyinmind of csdn.net
 *
 */
public final class Decode extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();

    public Decode(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String cipher = req.getString(CIPHER);
        try {
            String plain = RootKeystore.instance().decode(cipher);
            Map<String, Object> data = MapBuilder.of(PLAIN, plain);
            return futureResult(data);
        } catch (Exception e) {
            LOG.error("Fail to decode `{}`", cipher, e);
            return futureResult(RetCode.INTERNAL_ERROR, "fail to decode");
        }
    }
}
