package cn.net.zhijian.mesh.server;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.bean.ProxyResponse;
import cn.net.zhijian.mesh.bean.ServerSecurity;
import cn.net.zhijian.mesh.client.HttpClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.process.HttpXTcpProcessor;
import cn.net.zhijian.mesh.frm.service.backend.BackendBase;
import cn.net.zhijian.mesh.prot.tcp.ITcpProtocol;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.Totp;
import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.util.ValParser;
import io.netty.handler.ssl.SslContext;

/**
 * 测试服务端基本接口调用功能，包括TCP、HTTP，注意以下几点
 * 1）bios中的service/nodes接口，始终返回localhost:8523；
 * 2）user中/verify 始终返回成功；
 */
public class ServerTest extends UnitTestBase implements ITcpProtocol {
    private static final String TCPCLIENT_RESOURCE = "admin";
    private static final int TCPCONTROL_SUBCOMMAND = 1024;
    
    private static ServiceServer serviceServer;
    private static ServerSecurity srvSecurity;
    private static TcpChannel tcpChannel;
    private static HttpChannel httpChannel;
    private static String HTTP_SRV_ADDR = "localhost:" + HTTP_PORT;
    private static String totpKey;
    private static final Totp totp = new Totp(6, 60);

    @BeforeAll
    public static void init() throws Exception {
        BackendBase.createService(getHomeDir());
        ServiceServer ss = null;
        try {
            ss = new ServiceServer(getHomeDir());
        } catch (MeshException e) {
            e.printStackTrace(); //如果有异常，整个无法运行下去
        }
        //ss.addService(omSi);
        serviceServer = ss;
        ServerSecurity.init(configDir);
        srvSecurity = ServerSecurity.load();
        tcpChannel = new TcpChannel(serviceServer, TCP_PORT, null);
        httpChannel = new HttpChannel(serviceServer, HTTP_PORT);

        HttpXTcpProcessor.setTcpChannel(tcpChannel);
        SslContext context = srvSecurity.createSslContext().get();
        if(context == null) {
            fail("SslContext is null, can't start tcp&http server");
            return;
        }
        boolean r = tcpChannel.start(context);
        System.out.println("tcpChannel.start:" + r);
        r = httpChannel.start(context);
        System.out.println("tcpChannel.start:" + r);
        File f = new File(FileUtil.addPath(configDir, "totp.key"));
        totpKey = FileUtil.readFile(f, IConst.DEFAULT_CHARSET);
        System.out.println("Server totp key is " + totpKey);
        String code = totp.generateCode(totpKey);
        r = serviceServer.startServices(code);
        if(!r) {
            fail("realServer.startServices:" + r + ",totp code:" + code);
        }
    }
    
    @AfterAll
    public static void destroy() {
        serviceServer.destroy();
        tcpChannel.destroy();
        httpChannel.destroy();
    }

    @Test
    public void testNamespace() {
        String url = "http://" + HTTP_SRV_ADDR + "/user/api/nameSpaceTest";
        Map<String, String> headers = MapBuilder.of("cid", "40");
        NodeAddress node = new NodeAddress("user", HTTP_SRV_ADDR, 1000, 0);
        try {
            HandleResult hr = HttpClient.getFrom(node, url, headers, "namespace").get(50, TimeUnit.SECONDS);
            System.out.println("get result:" + hr.toString());
            assertEquals(RetCode.OK, hr.code);
            assertTrue(hr.data.get("test") != null);
            assertTrue(hr.data.get("macro") != null);
            Map<String, Object> res = ValParser.getAsObject(hr.data, "login_test");
            assertTrue(res != null && res.containsKey("access_token"));
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }
    
    
    @Test
    public void testCallPublicApi() {
        String url = "http://" + HTTP_SRV_ADDR + "/user/api/initialize1";
        Map<String, String> headers = MapBuilder.of("cid", "40");
        NodeAddress node = new NodeAddress("user", HTTP_SRV_ADDR, 1000, 0);
        try {
            HandleResult hr = HttpClient.getFrom(node, url, headers, "initialize").get(50, TimeUnit.SECONDS);
            System.out.println("get result:" + hr.toString());
            assertEquals(RetCode.OK, hr.code);
            assertEquals(hr.data.get("result"), RetCode.OK);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }
    
    @Test
    public void testGetFile() {
        String url = "http://" + HTTP_SRV_ADDR + "/user/test.txt";
        NodeAddress node = new NodeAddress("user", HTTP_SRV_ADDR, 1000, 0);
        Map<String, String> headers = MapBuilder.of("cid", "40");
        try {
            ProxyResponse r = HttpClient.getFileFrom(node, url, headers, "initialize").get(50, TimeUnit.SECONDS);
            if(r == null || r.body == null) {
                fail("No response or response body is null");
                return;
            }
            String s = new String(r.body, IConst.DEFAULT_CHARSET);
            System.out.println("get result:" + s + ",headers:" + r.headers);
            assertEquals(s, "abcd");
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }
    
    @Test
    public void testCallServerWithInvalidJson() {
        String url = "http://" + HTTP_SRV_ADDR + "/user/api/login";
        Map<String, String> headers = MapBuilder.of("cid", "40");
        NodeAddress node = new NodeAddress("user", HTTP_SRV_ADDR, 1000, 0);
        try {
            HandleResult hr = HttpClient.postTo(node, url, headers, "application/json", "aaa", "login").get(5, TimeUnit.SECONDS);
            System.out.println("get result:" + hr.toString());
            assertTrue(hr.code == RetCode.WRONG_JSON_FORMAT);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }
    
    @Test
    public void testTcpServerBase() {
        try(Socket sck = new Socket("localhost", TCP_PORT)) {
            DataOutputStream os = new DataOutputStream(sck.getOutputStream());   //输出流
            DataInputStream is = new DataInputStream(sck.getInputStream());
            int reqId = 0;
            System.out.println("testTcpServerConnect");
            tcpServerConnect(os, is, reqId++);

            System.out.println("testTcpServerHeartbeat");
            tcpServerHeartbeat(os, is, reqId++);
            
            System.out.println("testTcpServerDisconnect");
            tcpServerDisconnect(os, is, reqId++);
        } catch(Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }
    
    @Test
    public void testTcpServerApiCall() {
        try(Socket sck = new Socket("localhost", TCP_PORT)) {
            DataOutputStream os = new DataOutputStream(sck.getOutputStream());   //输出流
            DataInputStream is = new DataInputStream(sck.getInputStream());
            int reqId = 0;
            System.out.println("testTcpServerConnect");
            tcpServerConnect(os, is, reqId++);
            
            System.out.println("testTcpServerApiCall");
            tcpServerApiCall(os, is, reqId++);
            
            System.out.println("testTcpServerDisconnect");
            tcpServerDisconnect(os, is, reqId++);
        } catch(Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testSendMsgToTcpClientByHttp() {
        System.out.println("testTcpServerHttpXTcp");
        try(Socket sck = new Socket("localhost", TCP_PORT)) {
            DataOutputStream os = new DataOutputStream(sck.getOutputStream());   //输出流
            DataInputStream is = new DataInputStream(sck.getInputStream());
            int reqId = 0;
            System.out.println("tcpServerConnect");
            tcpServerConnect(os, is, reqId++);
            
            System.out.println("tcpServerSendMsgTcpClient");
            tcpServerSendMsgTcpClient(os, is);
            
            System.out.println("tcpServerHeartbeat");
            tcpServerHeartbeat(os, is, reqId++);
            
            System.out.println("tcpServerDisconnect");
            tcpServerDisconnect(os, is, reqId++);
        } catch(Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }
    
    private void tcpServerConnect(DataOutputStream os, DataInputStream is, int reqId) throws IOException {
        /* len(4) + cmd(1) + reqId(4) + ver(4)
         * + resource_len
         * + resource(resoure_name + : + access_code + @ + cid)
         */
        String s = TCPCLIENT_RESOURCE + ":123456@40";
        byte[] resource = s.getBytes(IConst.DEFAULT_CHARSET);
        os.writeInt(1 + 4 + 4 + 4 + resource.length); //len
        os.writeByte(CONNECT); //cmd
        os.writeInt(reqId); //reqId
        os.writeInt(0x1000); //version,0.1.0
        os.writeInt(resource.length);
        os.write(resource);
        os.flush();

        /*
         * total_len(4) + cmd(1) + reqid(4)
         * + statusCode(4)
         * + head_len(4) [+ headers]
         * + body_len(4) [+ body] 
         * len的值不包括len本身的4字节
         */
        
        int len = is.readInt();
        assertTrue(len > 0);
        int cmd = ((int)is.readByte()) & 0xff;
        assertEquals(cmd, CONNECT);
        int rid = is.readInt();
        assertEquals(reqId, rid);
        int statusCode = is.readInt();
        assertEquals(statusCode, 200);
        len = is.readInt(); //head_len
        assertEquals(len, 0);
        len = is.readInt(); //body_len
        assertTrue(len > 0);
        byte[] buf = new byte[len];
        is.readFully(buf);
        Map<String, Object> hr = JsonUtil.jsonToMap(buf);
        int code = ValParser.getAsInt(hr, HandleResult.CODE);
        assertEquals(RetCode.OK, code);
        Map<String, Object> data = ValParser.getAsObject(hr, "data");
        assertTrue(data != null && data.containsKey("access_token"));        
    }
    
    private void tcpServerHeartbeat(DataOutputStream os, DataInputStream is, int reqId) throws IOException {
        /* len(4) + cmd(1) + reqId(4)
         */
        os.writeInt(1 + 4); //len
        os.writeByte(HEARTBEAT); //cmd
        os.writeInt(reqId); //reqId
        os.flush();

        /*
         * total_len(4) + cmd(1) + reqid(4)
         * + statusCode(4)
         * + head_len(4) [+ headers]
         * + body_len(4) [+ body] 
         */
        int len = is.readInt();
        assertTrue(len > 0);
        int cmd = ((int)is.readByte()) & 0xff;
        assertEquals(cmd, HEARTBEAT);
        int rid = is.readInt();
        assertEquals(reqId, rid);
        int statusCode = is.readInt();
        assertEquals(statusCode, 200);
        //注意后面还有两个int，都是0，如果不读出来，后面就不能正常读
        is.skip(8);
//        len = is.readInt(); //head_len
//        assertEquals(len, 0);
//        len = is.readInt(); //body_len
//        assertEquals(len, 0);
    }
    
    private void tcpServerDisconnect(DataOutputStream os, DataInputStream is, int reqId) throws IOException {
        /* len(4) + cmd(1) + reqId(4)
         */
        os.writeInt(1 + 4); //len
        os.writeByte(DISCONNECT); //cmd
        os.writeInt(reqId); //reqId
        os.flush();

        /*
         * total_len(4) + cmd(1) + reqid(4)
         * + statusCode(4)
         */
        int len = is.readInt();
        assertTrue(len > 0);
        int cmd = ((int)is.readByte()) & 0xff;
        assertEquals(cmd, DISCONNECT);
        int rid = is.readInt();
        assertEquals(reqId, rid);
        int statusCode = is.readInt();
        assertEquals(statusCode, 200);
        is.skip(8);
//        len = is.readInt(); //head_len
//        assertEquals(len, 0);
//        len = is.readInt(); //body_len
//        assertEquals(len, 0);
    }
    
    private void tcpServerApiCall(DataOutputStream os, DataInputStream is, int reqId) throws IOException {
        /*
         * 客户端的协议请求命令，与http的method一一对应
         * len(4，不包括len本身4字节的报文长度)
         * + cmd(1) + reqid(4，客户端的消息编号)
         * + url_len(4) + url
         * + header_len(4) + headers
         * + body_len(4) + body
         */
        String s = "/user/api/getBaseInfo?uid=1";
        byte[] url = s.getBytes();
        Map<String, String> sm = MapBuilder.of("cid", "40", "access_token", "11111");
        byte[] headers = JsonUtil.objToBytes(sm);
        
        os.writeInt(1 + 4 + 4 + url.length + 4 + headers.length + 4/*body_len*/); //len
        os.writeByte(GET); //cmd
        os.writeInt(reqId); //reqId
        os.writeInt(url.length);
        os.write(url);
        os.writeInt(headers.length);
        os.write(headers);
        os.writeInt(0); //body_len
        os.flush();

        /*
         * total_len(4) + cmd(1) + reqid(4)
         * + statusCode(4)
         * + head_len(4) [+ headers]
         * + body_len(4) [+ body] 
         * len的值不包括len本身的4字节
         */
        int len = is.readInt();
        System.out.println("readable:" + is.available() + ",len:" + len);
        assertTrue(len > 0);
        int cmd = ((int)is.readByte()) & 0xff;
        assertEquals(cmd, GET);
        int rid = is.readInt();
        assertEquals(reqId, rid);
        int statusCode = is.readInt();
        assertEquals(statusCode, 200);
        len = is.readInt(); //head_len
        byte[] buf;
        if(len > 0) {
            buf = new byte[len];
            is.read(buf);
            Map<String, String> hh = JsonUtil.jsonToStrMap(buf);
            System.out.println("call api response headers:" + hh.toString());
        }
        len = is.readInt(); //body_len
        assertTrue(len > 0);
        buf = new byte[len];
        is.read(buf);
        Map<String, Object> hr = JsonUtil.jsonToMap(buf);
        System.out.println("result:" + hr.toString());
        assertEquals(RetCode.OK, ValParser.getAsInt(hr, HandleResult.CODE));
        Map<String, Object> data = ValParser.getAsObject(hr, HandleResult.DATA);

        System.out.println("call api response body:" + (data == null ? "" : data.toString()));
        assertTrue(data != null && data.containsKey("account"));
    }
    
    private void tcpServerSendMsgTcpClient(DataOutputStream os, DataInputStream is) throws Exception {
        CountDownLatch waiter = new CountDownLatch(1);
        new Thread() {
            public void run() {
                try {
                    handleControlRequest(os, is);
                } catch(Exception e) {
                    e.printStackTrace();
                } finally {
                    waiter.countDown();
                }
            }
        }.start();

        //http发起请求，转给tcp长连接客户端
        URL httpUrl = new URI("http://localhost:" + HTTP_PORT + "/user/api/ctrl_test").toURL();  
        HttpURLConnection connection = (HttpURLConnection) httpUrl.openConnection();  
        connection.setRequestMethod("GET");  
        connection.setRequestProperty(HEAD_RESOURCE, TCPCLIENT_RESOURCE);
        connection.setRequestProperty(HEAD_COMMAND, "" + TCPCONTROL_SUBCOMMAND);
   
        //http读取响应
        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));  
        String inputLine;  
        StringBuffer response = new StringBuffer();  

        while ((inputLine = in.readLine()) != null) {  
            response.append(inputLine);  
        }
        Map<String, Object> hr = JsonUtil.jsonToMap(response.toString());
        assertTrue(hr != null);
        assertEquals(RetCode.OK, ValParser.getAsInt(hr, HandleResult.CODE));
        Map<String, Object> data = ValParser.getAsObject(hr, HandleResult.DATA);
        assertTrue(data != null && data.containsKey("test2"));
        
        in.close();
        connection.disconnect();
        waiter.await();
    }
    
    private void handleControlRequest(DataOutputStream os, DataInputStream is) throws IOException {
        /*
         * 作为长连接客户端处理http客户端的请求
         * total_len(4) + cmd(1) + reqid(4)
         * + value(4) //GET/PUT/POST/DELETE
         * + body_len(4) [+ body] 
         * len的值不包括len本身的4字节
         */
        int len = is.readInt();
        System.out.println("readable:" + is.available() + ",len:" + len);
        assertTrue(len > 0);
        int cmd = ((int)is.readByte()) & 0xff;
        assertEquals(cmd, CONTROL);
        int reqId = is.readInt();
        int value = is.readInt();
        assertEquals(value, TCPCONTROL_SUBCOMMAND);
        len = is.readInt(); //head_len
        byte[] buf;
        if(len > 0) {
            buf = new byte[len];
            is.read(buf);
            Map<String, String> headers = JsonUtil.jsonToStrMap(buf);
            System.out.println("call api response headers:" + headers.toString());
        }
        len = is.readInt(); //body_len
        if(len > 0) {
            buf = new byte[len];
            is.read(buf);
            Map<String, Object> params = JsonUtil.jsonToMap(buf);
            System.out.println("call tcpx api response result:" + params.toString());
        }

        /*
         * 客户端的协议请求命令，与http的method一一对应
         * len(4，不包括len本身4字节的报文长度)
         * cmd(1) + reqid(4，与客户端发给服务端的编号是分开计数的)
         * + value(4，CLOSE时表示等待多少秒之后再次连接，
         *    客户端可以基于此值按尝试次数调整重连时长，比如每次重试都乘以2；
         *    CONTROL请求时，为GET/DELETE/POST/PUT之一)
         * + head_len(4) + headers
         * + body_len(4) + body
         */
        Map<String, Object> mo = MapBuilder.of("test1", 1, "test2", "t");
        byte[] body = JsonUtil.objToBytes(mo);

        os.writeInt(1 + 4 + 4/*value*/ + 4 + 0 + 4/*body_len*/ + body.length); //len
        os.writeByte(CONTROL); //cmd
        os.writeInt(reqId); //reqId
        os.writeInt(200); //status_code
        os.writeInt(0); //head_len
        os.writeInt(body.length); //body_len
        os.write(body);
        
        os.flush();        
    }
}
