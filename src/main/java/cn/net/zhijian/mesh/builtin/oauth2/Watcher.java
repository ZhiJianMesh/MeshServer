package cn.net.zhijian.mesh.builtin.oauth2;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.mesh.frm.intf.IServiceWatcher;
import cn.net.zhijian.mesh.frm.tokenworker.CodebookTokenWorker;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * oauth2服务看护类
 * 在服务启动前，先用本地内置的codebook，
 * 其他codebook存在bios中，需要用根密钥解密后才可以使用
 * @author flyinmind of csdn.net
 *
 */
public final class Watcher extends IServiceWatcher.DefaultServiceWatcher {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String CFG_CODEBOOKS = "codebooks";

    @Override
    public CompletableFuture<HandleResult> afterLoad(IServiceServer server, ServiceInfo si, String pwd) {
        return super.afterLoad(server, si, pwd).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to call super.afterLoad of {},result:{}", si.name, hr.brief());
                return CompletableFuture.completedFuture(null);
            }
            return BiosClient.getConfig(si, CFG_CODEBOOKS, false).thenComposeAsync(cipheredCodebooks -> {
                if(StringUtil.isEmpty(cipheredCodebooks)) {
                    return createCodebook(si);
                }

                return BiosClient.rootDecode(si, cipheredCodebooks).thenComposeAsync(decodeHr -> {
                    if(decodeHr.code != RetCode.OK || decodeHr.data == null) {
                        LOG.error("Fail to decode codebooks of {},result:{}", si.name, decodeHr.brief());
                        return createCodebook(si);
                    }
                    String plain = ValParser.getAsStr(decodeHr.data, IConst.PLAIN);
                    if(parseCodebook(si, plain)) {
                        return HandleResult.future();
                    }
                    LOG.error("Fail to parse codebooks of {},create it", si.name);
                    return createCodebook(si);
                }, Pool);
            }, Pool).whenCompleteAsync((h, e) -> {
                if(e != null) {
                    LOG.error("Fail to parse code book of {} from bios", si.name, e);
                }
            }, Pool);
        }, Pool);
    }
    
    private boolean parseCodebook(ServiceInfo si, String plain) {
        try {
            List<byte[]> codebooks = CodebookTokenWorker.parseCodebooks(plain);
            if(codebooks != null && !codebooks.isEmpty()) {
                AuthBase.setTokenWoker(new CodebookTokenWorker(codebooks));
                LOG.info("Success to parse codebooks in {}, total {}", si.name, codebooks.size());
                return true;
            }
            LOG.error("Fail to parse codebooks in {},src:{}", si.name, plain);
        } catch (IOException e) {
            LOG.error("Fail to parse codebooks in {},src:{}", si.name, plain, e);
        }
        return false;        
    }
    
    private CompletableFuture<HandleResult> createCodebook(ServiceInfo si) {
        LOG.info("No valid codebooks for {} in bios,create it,then encode it by root key", si.name);
        String plain = CodebookTokenWorker.generateCodebooks(3);
        return BiosClient.rootEncode(si, plain).thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK || hr.data == null) {
                LOG.error("Fail to encode codebooks of {},result:{}", si.name, hr.brief());
                return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to encode codebook");
            }
            String cipheredCbs = ValParser.getAsStr(hr.data, IConst.CIPHER);
            return BiosClient.putConfig(si, CFG_CODEBOOKS, cipheredCbs).thenApplyAsync(result -> {
                if(!result) {
                    LOG.error("Fail to save codebooks of {}", si.name);
                    return new HandleResult(RetCode.INTERNAL_ERROR, "fail to save codebooks");
                }
                if(parseCodebook(si, plain)) {
                    return HandleResult.OK;
                }
                return new HandleResult(RetCode.INTERNAL_ERROR, "invalid codebooks");
            }, Pool);
        }, Pool);
    }
}
