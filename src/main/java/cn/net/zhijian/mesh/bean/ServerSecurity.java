package cn.net.zhijian.mesh.bean;

import org.slf4j.Logger;

import java.io.File;
import java.security.KeyStore;
import java.util.concurrent.CompletableFuture;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.ValParser;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;

/**
 * 非常重要的类，控制系统的安全证书。
 * 同一套部署中，使用相同的ServerSecurity，因为使用相同的keystore文件
 * @author flyinmind of csdn.net
 *
 */
public class ServerSecurity implements IThreadPool {
    private static final String SERVER_CA_FILE = "server.bks";
    private static final Logger LOG = LogUtil.getInstance();
    private static final char[] SERVER_KS_PWD = "zhijianmesh.SRV".toCharArray(); //写死，且jks/bks的keypass与storepass必须相同

    private static File ksFile; //ca文件路径
    private final char[] storePass;
    private final char[] keyPass;
    public final String tlsVer;

    private KeyStore keyStore = null;

    private ServerSecurity(char[] storePass, char[] keyPass, String tlsVer) {
        this.storePass = storePass;
        this.keyPass = keyPass;
        this.tlsVer = tlsVer;
    }

    /**
     * 如果存在本地keystore文件，则从本地加载，
     * 否则以company身份从云侧下载新的CA(本质为keystore文件)，并存于本地
     * @return keystore 密钥库
     */
    private CompletableFuture<KeyStore> loadCA() {
        if(keyStore != null) {
            return CompletableFuture.completedFuture(keyStore);
        }

        if(ksFile.exists()) {
            keyStore = readKeyStore();
            return CompletableFuture.completedFuture(keyStore);
        }

        //根公司必须有ca文件，主bios登录时已下载，所以都不会走到这里。
        //如果有多个公司，使用任意一个公司的ca
        CompanyInfo localCompany = CompanyInfo.instance();
        AccessToken adminToken = localCompany.adminToken(IConst.SERVICE_COMPANY);
        if(adminToken == null) {
            LOG.error("Fail to create adminToken");
            return CompletableFuture.completedFuture(null);
        }
        //只要保证所有CA的根证书是相同的即可
        //私有云CA，只需要保证每个都不同即可
        //公有云CA，每个实例可以相同，也可以不同，只需要对外不可见即可
        ServiceReqBuilder req = ServiceClient.backendReqBuilder(IConst.SERVICE_COMPANY)
                .cid(localCompany.id)
                .token(adminToken.generate())
                .url("/company/getca")
                .traceId(IConst.SERVICE_COMPANY + '_' + localCompany.id);

        return ServiceClient.cloudGet(req).thenApplyAsync(hr -> {
            if(hr.code != RetCode.OK || hr.data == null) {
                LOG.error("Fail to get CA,result:{}", hr.brief());
                return null;
            }
            String ca = ValParser.getAsStr(hr.data, "ca");
            byte[] caBytes = ByteUtil.base642bin(ca);
            saveServerCA(caBytes);
            return readKeyStore();
        }, Pool);
    }

    public static void saveServerCA(byte[] ca) {
        FileUtil.writeFile(ksFile, ca);
    }

    private KeyStore readKeyStore() {
        LOG.debug("Read keystore {}", ksFile);
        try {
            return Ecc.loadBks(ksFile, storePass);
        } catch (Exception e) {
            LOG.error("Fail to load ksFile {}", ksFile, e);
            return null;
        }
    }

    /**
     * 创建SSL上下文，用于加密
     * @return ssl上下文
     */
    public CompletableFuture<SslContext> createSslContext() {
        return loadCA().thenApplyAsync(ks -> {
            if(ks == null) {
                LOG.error("Fail to get server keystore from {}", ksFile);
                return null;
            }
            keyStore = ks;
            return genSslContext();
        }, Pool);
    }
    
    public SslContext genSslContext() {
        try {
            SSLContext sslContext = SSLContext.getInstance(tlsVer);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keyPass);
            sslContext.init(kmf.getKeyManagers(), null, SecureUtil.getRandom());

            SslContextBuilder builder = SslContextBuilder.forServer(kmf)
                    .sslProvider(SslProvider.JDK);
            if(AbsPlatform.alpnSupported()) { //只有支持ALPN，才能支持HTTP2协议协商，本实现不考虑HTTP协议的协商，因为浏览器普遍不支持
                builder.ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                       .applicationProtocolConfig(new ApplicationProtocolConfig(
                        Protocol.ALPN,
                        // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                        SelectorFailureBehavior.NO_ADVERTISE,
                        // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                        SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2,
                        ApplicationProtocolNames.HTTP_1_1
                ));
            } else {
                LOG.warn("ALPN not supported, can't use http2");
            }
            return builder.build();
        } catch(Exception e) {
            LOG.error("Fail to create ssl context", e);
        }
        return null;
    }

    public static void init(String confPath) {
        ksFile = new File(FileUtil.addPath(confPath, SERVER_CA_FILE));
    }
    
    public static File configFile() {
        return ksFile;
    }
    
    public static ServerSecurity load() {
        //服务端安全相关的处理
        String tlsVer = SecureUtil.getMaxTlsVer();
        LOG.debug("Server tls version is {}", tlsVer);
        //SERVER_KS_FILE放在conf下，会变化的文件，所以不属于assets
        //正常情况，每个公司server.bks都不同，如果相同，则会导致传输私钥泄露，https等于摆设
        //但是同一个私网多家公司共用的情况，它们只能共用ca，这几家公司的传输是内部透明的
        LOG.debug("Server ca {}", ksFile);
        return new ServerSecurity(SERVER_KS_PWD, SERVER_KS_PWD, tlsVer);
    }
}
