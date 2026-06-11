package cn.net.zhijian.mesh.frm.abs;

import java.io.File;
import java.net.InetAddress;
import java.util.List;

import cn.net.zhijian.util.IPUtil;

public abstract class AbsPlatform {
    private static AbsPlatform instance;
    public static void init(AbsPlatform instance) {
        AbsPlatform.instance = instance;
    }
    /**
     * 创建临时目录，比如临时生成文件给业务下载接口中的临时目录
     * @param prefix 前缀
     * @return 路径
     */
    protected abstract File absCreateTempDirectory(String prefix);
    /**
     * 在系统的临时目录创建临时文件
     * @param prefix 前缀
     * @return 文件
     */
    protected abstract File absCreateTempFile(String prefix);

    /**
     * 存放数据库文件的根路径，此路径只对单文件数据库有效，比如sqlite
     * @return 文件
     */
    protected abstract String absDbRoot();

    /**
     * java中返回jvm版本，android中返回ART（Android RunTime）版本
     * @return 版本号
     */
    protected abstract String absVmVersion();

    /**
     * 存放服务客户端zip包的根路径
     * @return 文件
     */
    protected abstract String absClientsRoot();        

    /**
     * 本机地址
     * @return 地址，不包括端口号
     */
    protected abstract String absMyIp();

    /**
     * IPv6全球单播地址，网关上做了端口映射且开放了IPv6防火墙时，可以外网访问
     * @return 地址 不包括端口号
     */
    protected abstract String absGlobalIPv6();
    /**
     * 系统是否支持alpn
     * @return 是否支持
     */
    protected abstract boolean isAlpnSupported();
    /**
     * 系统是否支持压缩
     * @return 是否压缩
     */
    protected abstract boolean isCompressSupported();
    
    /**
     * 运行环境
     * @return 环境名称
     */
    protected abstract String absEnvironment();

    public static File createTempDirectory(String prefix) {
        return instance.absCreateTempDirectory(prefix);
    }

    public static File createTempFile(String prefix) {
        return instance.absCreateTempFile(prefix);
    }

    public static String dbRoot() {
        return instance.absDbRoot();
    }

    public static String vmVersion() {
        return instance.absVmVersion();
    }
    
    public static String clientsRoot() {
        return instance.absClientsRoot();
    }

    public static String myIp() {
        return instance.absMyIp();
    }
    
    public static String globalIPv6() {
        return instance.absGlobalIPv6();
    }

    public static boolean isLocalIp(String addr) {
        int pos;
        String ip;
        if((pos = addr.indexOf("]:")) > 0) { //ipv6并且带有端口的情况[aaa:xxxx:yyy]:port
            ip = addr.substring(1, pos); //去除前面的'['
        } else if((pos = addr.indexOf(':')) > 0) { //ipv4带有端口，a.b.c.c:port
            ip = addr.substring(0, pos);
        } else {
            ip = addr;
        }
        List<InetAddress> list = IPUtil.getIPList();
        for(InetAddress a : list) {
            if (ip.equals(a.getHostAddress())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用于判断是否可以支持http2协议协商
     * @return true表示可以支持
     */
    public static boolean alpnSupported() {
        return instance.isAlpnSupported();
    }

    /**
     * http 服务器传输是否可以支持压缩，在安卓下不能支持
     * @return true表示可以支持
     */
    public static boolean compressSupported() {
        return instance.isCompressSupported();
    }
    
    public static String environment() {
        return instance.absEnvironment();
    }
}
