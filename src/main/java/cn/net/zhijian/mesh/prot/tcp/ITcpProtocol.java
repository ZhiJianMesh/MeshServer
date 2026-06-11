package cn.net.zhijian.mesh.prot.tcp;

import java.util.HashMap;
import java.util.Map;

/**
 * 私有云通过公有云的bridge服务给处于外网的用户提供服务。
 * 外网用户在访问公有云httpdns时，需要提供接入码；
 * 每次请求bridge服务时，也需要提供接入码，否则拒绝服务。
 * @author flyinmind of csdn.net
 *
 */
public interface ITcpProtocol {
    /*
     * 客户端的连接操作命令
     * len(4，不包括len本身四字节的报文长度，netty解析时已从ByteBuf中扣除了)
     * + cmd(1) + reqid(4)
     */
    int CONNECT = 0; //端侧连接认证请求，携带资源名称、接入码、协议版本
    int DISCONNECT = 1; //端侧断开连接请求
    int HEARTBEAT = 2; //端侧发给云侧的心跳

    /*
     * 客户端的协议请求命令，与http的method一一对应
     * len(4，不包括len本身4字节的报文长度)
     * + cmd(1) + reqid(4，客户端的消息编号)
     * + url_len(4) + url
     * + header_len(4) + headers
     * + body_len(4) + body
     * 
     * 服务端被动发给客户端响应报文，只有在客户端请求时才会在响应时发送
     * len(4，不包括len本身4字节的报文长度)
     * + cmd(1，与请求的命令字一致)
     * + reqid(4，与客户端发给服务端的编号是分开计数的)
     * + status_code(4，与http响应码一致)
     * + header_len(4) + headers
     * + body_len(4) + body
     */
    int GET = 3; //客户端get请求，携带headers
    int POST = 4; //客户端post请求，携带headers与body
    int DELETE = 5; //客户端delete请求，携带headers
    int PUT = 6; //客户端PUT请求，携带headers与body

    /*
     * 服务端主动发给客户端的控制命令。
     * len(4，不包括len本身4字节的报文长度)
     * + cmd(1) + reqid(4，与客户端发给服务端的编号是分开计数的)
     * + value(4，CLOSE时表示等待多少秒之后再次连接，
     *    客户端可以基于此值按尝试次数调整重连时长，比如每次重试都乘以2；
     *    CONTROL请求时，为子命令字，由系统自己定义；响应时为status code，通常为200)
     * + head_len(4) + headers
     * + body_len(4) + body
     */
    int CLOSE = 7; //服务侧主动断开连接，携带下次尝试时间间隔
    int CONTROL = 8; //控制命令，携带headers与body，由http客户端发起，服务器转给tcp长连接客户端
    
    Map<Integer, String> Names = new HashMap<Integer, String>() {{
        put(CONNECT, "CONNECT");
        put(DISCONNECT, "DISCONNECT");
        put(HEARTBEAT, "HEARTBEAT");
        put(GET, "GET");
        put(POST, "POST");
        put(DELETE, "DELETE");
        put(PUT, "PUT");
        put(CONTROL, "CONTROL");
        put(CLOSE, "CLOSE");
    }};
    
    String HEAD_RESOURCE = "resource"; //接受消息的资源名称，就是长连接客户端连接时传递的资源名称
    String HEAD_COMMAND = "cmd"; //发给长连接客户端的命令字，是一个字符串
}
