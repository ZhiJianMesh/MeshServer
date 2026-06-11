package cn.net.zhijian.platform;

import java.security.SecureRandom;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * 服务端上下文
 * 记录公网网关的相关信息
 * @author flyinmind of csdn.net
 *
 */
public final class ServerContext {
    private static String accessCode = IConst.EMPTY_STR;//端侧在连接安卓服务侧时，服务侧鉴别端侧是否合法
    private static String gwAddr = IConst.EMPTY_STR;
    private static String companyName = IConst.EMPTY_STR;
    
    public static void setCompanyName(String name) {
        companyName = name;
    }

    public static String getCompanyName() {
        return companyName;
    }

    public static void setAccessCode(String code) {
        accessCode = code;
    }

    public static String getAccessCode() {
        if(StringUtil.isEmpty(accessCode)) {
            accessCode = genAccessCode();
        }
        return accessCode;
    }
    
    public static String genAccessCode() {
        String code = "";
        int c;
        SecureRandom rand = SecureUtil.getRandom();

        for(int i = 0; i < 8; i++) {
            c = rand.nextInt(36);
            if(c < 26) {
                code += (char)('A' + c);
            } else {
                code += (char)('0' + (c - 26));
            }
        }
        return code.toUpperCase();
    }
    
    /**
     * 设置外网网关地址，在使用长连接网关实现内网穿透时，
     * 当BridgeGW从公有云获得地址并成功连接后，就会调用setGwAddr，
     * 在端侧调用probe，判断req是来自外部，则返回的地址都是gwAddr
     * @param addr 网关地址
     */
    public static void setGwAddr(String addr) {
        gwAddr = addr;
    }
    
    public static String getGwAddr() {
        return gwAddr;
    }
}
