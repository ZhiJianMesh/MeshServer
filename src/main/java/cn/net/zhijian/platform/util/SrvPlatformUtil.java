package cn.net.zhijian.platform.util;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.platform.IPlatformConst;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.IPUtil;
import cn.net.zhijian.util.LogUtil;
import io.netty.handler.ssl.SslProvider;

/**
 * 平台工具类，为了适应不同的平台中的差异性
 * @author flyinmind of csdn.net
 *
 */
public final class SrvPlatformUtil extends AbsPlatform {
    private static final Logger LOG = LogUtil.getInstance();

    private final Map<String,Long> ipv6List = new HashMap<>(); //记录老的ipv6列表，用于寻找不怎么变化的ipv6地址
    private final String workDir;
    private final boolean alpnSupported = SslProvider.isAlpnSupported(SslProvider.JDK);
    
    public SrvPlatformUtil(String workDir) {
        this.workDir = workDir;
        List<String> addrs = IPUtil.getIpv6Address(true);
        for(String ip : addrs) {
            ipv6List.put(ip, System.currentTimeMillis());
        }
    }

    /**
     * 获取本机IPv4地址
     * @return 本机IPv4地址，null表示无网络连接
     */
    @Override
    public String absMyIp() {
        try {
            InetAddress addr = InetAddress.getLocalHost();
            return addr.getHostAddress();
        } catch (UnknownHostException e) {
            LOG.error("Fail to get local machine address", e);
            return null;
        }
    }
    
    /**
     * IPv6全球单播地址，网关上做了端口映射且开放了IPv6防火墙时，可以外网访问
     * @return 地址
     */
    @Override
    public String absGlobalIPv6() {
        List<String> addrs = IPUtil.getIpv6Address(true);
        if(addrs == null || addrs.isEmpty()) {
            return null;
        }
        //ipv6有永久地址与临时地址，在网关上合理设置后，两个地址都可以从外网直接访问
        //永久地址会暴露隐私，通常访问外网用临时地址，作为服务器尽量使用永久地址
        //第一次任选一个，后面如果某个地址长期不变化，则返回它
        String stable = addrs.get(0);
        Long oldTime;
        long stableTime = Long.MAX_VALUE;
        for(String ip : addrs) {
            oldTime = ipv6List.get(ip);
            if(oldTime != null) {
                if(oldTime < stableTime) {
                    stable = ip;
                    stableTime = oldTime;
                }
            } else {
                ipv6List.put(ip, System.currentTimeMillis());
            }
        }
        return stable;
    }

    @Override
    public boolean isAlpnSupported() {
        return alpnSupported;
    }

    @Override
    public boolean isCompressSupported() {
        return true;
    }

    @Override
    protected File absCreateTempDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toFile();
        } catch (IOException e) {
            LOG.error("Fail to create {} temp directory", prefix, e);
        }
        return null;
    }

    @Override
    protected File absCreateTempFile(String prefix) {
        try {
            return File.createTempFile(prefix, IConst.EMPTY_STR);
        } catch (IOException e) {
            LOG.error("Fail to create {} temp file", prefix, e);
        }
        return null;
    }

    @Override
    protected String absDbRoot() {
        return FileUtil.addPath(workDir, "dbroot");
    }

    @Override
    protected String absVmVersion() {
        return Runtime.version().toString();
    }

    @Override
    protected String absClientsRoot() {
        return FileUtil.addPath(workDir, "clients");
    }

    @Override
    protected String absEnvironment() {
        return IPlatformConst.EVM;
    }
}
