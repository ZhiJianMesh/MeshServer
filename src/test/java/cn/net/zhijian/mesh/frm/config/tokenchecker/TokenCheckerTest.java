package cn.net.zhijian.mesh.frm.config.tokenchecker;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsTokenChecker;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IOAuth;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.frm.intf.ITokenWorker;
import cn.net.zhijian.mesh.prot.http.HttpServerRequest4Test;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.UnitTestBase;

public class TokenCheckerTest extends UnitTestBase {
    //private static final int cid = 40;
    PartitionConfig partCfg = PartitionConfig.instance();
    ServiceInfo testSi1 = createTestServiceInfo("dir", "testservice1");
    ServiceInfo testSi2 = createTestServiceInfo("dir", "testservice2");
    
    //启动时偶尔会因为并发加载而出错，所以加此用例
    @Test
    public void testMultiThreadLoading() throws InterruptedException {
        AtomicInteger n = new AtomicInteger(0);
        String[] checkers = new String[] {
            IOAuth.OM_TOKEN_CHECKER, IOAuth.APP_TOKEN_CHECKER,
            IOAuth.COMPANY_TOKEN_CHECKER, IOAuth.BASEUSER_TOKEN_CHECKER,
            IOAuth.UNIUSER_TOKEN_CHECKER, IOAuth.OAUTH_TOKEN_CHECKER,
            IOAuth.USER_TOKEN_CHECKER, IOAuth.INIT_TOKEN_CHECKER,
            IOAuth.BACKEND_TOKEN_CHECKER
        };
        int N = 3;
        CountDownLatch counter = new CountDownLatch(N);
        for (int i = 0; i < N; i++) {
            new Thread(() -> {
                try {
                    for(String c : checkers) {
                        AbsTokenChecker tc = TokenCheckers.getChecker(c);
                        if(tc != null) {
                            n.incrementAndGet();
                        }
                    }
                } catch(Exception e) {
                    e.printStackTrace();
                }
                counter.countDown();
            }).start();
        }
        boolean res = counter.await(1000, TimeUnit.MILLISECONDS);
        assertTrue(res);
        assertEquals(n.get(), N * checkers.length);
    }
    
    @BeforeAll
    public static void testInitBackendTokenChecker() {
        String pwd = "test12349877";
        //此处不能直接使用BACKEND.instance，否则instance为null，还不知道原因
        BACKEND instance = (BACKEND) TokenCheckers.getChecker(IOAuth.BACKEND_TOKEN_CHECKER);
        CompanyInfo ci = CompanyInfo.instance();
        instance.setTokenPwd(ci, pwd);
        assertNull(instance.getTokenWorker(ci.id + 1)); 
        assertNotNull(instance.getTokenWorker(ci.id)); 
    }
    
    @Test
    public void testBackendTokenChecker() throws InterruptedException {
        BACKEND instance = (BACKEND) TokenCheckers.getChecker(IOAuth.BACKEND_TOKEN_CHECKER);
        CompanyInfo ci = CompanyInfo.instance();
        ITokenWorker tw = instance.getTokenWorker(ci.id);
        assertNotNull(tw);
        String token = CompanyInfo.adminToken(ci.id, tw, "testservice").generate();
        Map<String, String> headers = MapBuilder.of(IConst.HEAD_ACCESS_TOKEN, token, IConst.HEAD_CID, "" + ci.id);
        AbsServerRequest req = HttpServerRequest4Test.create(testSi1, headers, new HashMap<>());
        CountDownLatch counter = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);
        
        instance.check(req, token).whenCompleteAsync((at, e) -> {
            result.set(e == null && at != null);
            counter.countDown();
        }, IThreadPool.Pool);
        counter.await();
        assertTrue(result.get());
    }
    
    @Test
    public void testOMTokenChecker() throws InterruptedException {
        String omToken = omSi.createToken(10, testSi1.name);
        assertNotNull(omToken);
        APP instance = (APP) TokenCheckers.getChecker(IOAuth.OM_TOKEN_CHECKER);
        ITokenWorker old = instance.getTokenWorker(IConst.SERVICE_OM);
        instance.setTokenWorker(IConst.SERVICE_OM, omSi.tokenWorker);
        assertNotNull(instance);
        CompanyInfo ci = CompanyInfo.instance();
        Map<String, String> headers = MapBuilder.of(IConst.HEAD_ACCESS_TOKEN, omToken, IConst.HEAD_CID, "" + ci.id);
        AbsServerRequest req = HttpServerRequest4Test.create(testSi1, headers, new HashMap<>());
        CountDownLatch counter = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);
        
        instance.check(req, omToken).whenCompleteAsync((at, e) -> {
            if(e != null) {
                e.printStackTrace();
                result.set(false);
            } else {
                result.set(at != null);
            }
            counter.countDown();
        }, IThreadPool.Pool);
        counter.await();
        instance.setTokenWorker(IConst.SERVICE_OM, old);
        assertTrue(result.get());
    }
    
    //只容许自己调用自己
    @Test
    public void testSelfAppTokenChecker() throws InterruptedException {
        String appSelfToken = testSi1.tokenWorker.create(partCfg.partition,
                testSi1.name, testSi1.name, "", IOAuth.TOKENTYPE_SERVICE).generate();
        assertNotNull(appSelfToken);
        APP instance = (APP) TokenCheckers.getChecker(IOAuth.APP_TOKEN_CHECKER);
        assertNotNull(instance);
        CompanyInfo ci = CompanyInfo.instance();
        Map<String, String> headers = MapBuilder.of(IConst.HEAD_ACCESS_TOKEN, appSelfToken, IConst.HEAD_CID, "" + ci.id);
        AbsServerRequest req = HttpServerRequest4Test.create(testSi1, headers, new HashMap<>());
        CountDownLatch counter = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);
        
        instance.check(req, appSelfToken).whenCompleteAsync((at, e) -> {
            result.set(e == null && at != null);
            counter.countDown();
        }, IThreadPool.Pool);
        counter.await();
        assertTrue(result.get());
    }
    
    //容许指定的服务调用
    @Test
    public void testInterAppTokenChecker() throws InterruptedException {
        String appInterToken = testSi1.tokenWorker.create(partCfg.partition,
                testSi1.name, testSi2.name, "", IOAuth.TOKENTYPE_SERVICE).generate();
        assertNotNull(appInterToken);
        APP instance = (APP) TokenCheckers.getChecker(IOAuth.APP_TOKEN_CHECKER + "-" + testSi1.name);
        assertNotNull(instance);
        CompanyInfo ci = CompanyInfo.instance();
        instance.setTokenWorker(testSi1.name, testSi1.tokenWorker);
        Map<String, String> headers = MapBuilder.of(IConst.HEAD_ACCESS_TOKEN, appInterToken, IConst.HEAD_CID, "" + ci.id);
        AbsServerRequest req = HttpServerRequest4Test.create(testSi2, headers, new HashMap<>());
        CountDownLatch counter = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);
        
        instance.check(req, appInterToken).whenCompleteAsync((at, e) -> {
            result.set(e == null && at != null);
            counter.countDown();
        }, IThreadPool.Pool);
        counter.await();
        assertTrue(result.get());
    }
    
    //容许所有其他服务调用
    @Test
    public void testAllAppTokenChecker() throws InterruptedException {
        String appAllToken = testSi1.tokenWorker.create(partCfg.partition,
                testSi1.name, testSi2.name, "", IOAuth.TOKENTYPE_SERVICE).generate();
        assertNotNull(appAllToken);
        CompanyInfo ci = CompanyInfo.instance();
        APP instance = (APP) TokenCheckers.getChecker(IOAuth.APP_TOKEN_CHECKER + "-*");
        assertNotNull(instance);
        instance.setTokenWorker(testSi1.name, testSi1.tokenWorker);
        Map<String, String> headers = MapBuilder.of(IConst.HEAD_ACCESS_TOKEN, appAllToken, IConst.HEAD_CID, "" + ci.id);
        AbsServerRequest req = HttpServerRequest4Test.create(testSi2, headers, new HashMap<>());
        CountDownLatch counter = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);
        
        instance.check(req, appAllToken).whenCompleteAsync((at, e) -> {
            result.set(e == null && at != null);
            counter.countDown();
        }, IThreadPool.Pool);
        counter.await();
        assertTrue(result.get());
    }
    
    //只能验证私有云的公司级token，需要partition.cfg中partition>=250000
    @Test
    public void testCompanyTokenChecker() throws InterruptedException {
        CompanyInfo ci = CompanyInfo.instance();
        assertNotNull(ci);
        
        String comToken = ci.adminToken(testSi1.name).generate();
        assertNotNull(comToken);
        COMPANY instance = (COMPANY) TokenCheckers.getChecker(IOAuth.COMPANY_TOKEN_CHECKER);
        assertNotNull(instance);
        Map<String, String> headers = MapBuilder.of(IConst.HEAD_ACCESS_TOKEN, comToken, IConst.HEAD_CID, "" + ci.id);
        AbsServerRequest req = HttpServerRequest4Test.create(testSi1, headers, new HashMap<>());
        CountDownLatch counter = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);
        
        instance.check(req, comToken).whenCompleteAsync((at, e) -> {
            result.set(e == null && at != null);
            counter.countDown();
        }, IThreadPool.Pool);
        counter.await();
        if(!result.get()) {
            fail("CompanyInfo(id:" + ci.id + ",name:" + ci.name()
                + ",private_part:" + PartitionConfig.instance().isPrivate() + ")");
        }
    }
    
    //初始化接口
    @Test
    public void testInitTokenChecker() throws InterruptedException {
        String appSelfToken = testSi1.createToken(10, testSi1.name);
        assertNotNull(appSelfToken);
        String omToken = omSi.createToken(10, testSi1.name);
        assertNotNull(omToken);
        CompanyInfo ci = CompanyInfo.instance();
        
        //此处不能直接使用BACKEND.instance，否则instance为null，还不知道原因
        BACKEND bkd = (BACKEND) TokenCheckers.getChecker(IOAuth.BACKEND_TOKEN_CHECKER);
        ITokenWorker tw = bkd.getTokenWorker(ci.id);
        assertNotNull(tw);
        String bkdToken = CompanyInfo.adminToken(ci.id, tw, "testservice").generate();

        INIT instance = (INIT) TokenCheckers.getChecker(IOAuth.INIT_TOKEN_CHECKER);
        assertNotNull(instance);
        ITokenWorker old = instance.appTokenChecker.getTokenWorker(IConst.SERVICE_OM);
        instance.appTokenChecker.setTokenWorker(IConst.SERVICE_OM, omSi.tokenWorker);

        Map<String, String> headers = Map.of(IConst.HEAD_ACCESS_TOKEN, omToken, IConst.HEAD_CID, "" + ci.id);
        AbsServerRequest req = HttpServerRequest4Test.create(testSi1, headers, new HashMap<>());
        CountDownLatch counter = new CountDownLatch(3);
        AtomicInteger result = new AtomicInteger(0);
        
        instance.check(req, appSelfToken).whenCompleteAsync((at, e) -> {
            if(e == null && at != null) {
                result.incrementAndGet();
            } else {
                System.out.println("Fail to call appSelfToken.check");
            }
            counter.countDown();
        }, IThreadPool.Pool);
  
        instance.check(req, omToken).whenCompleteAsync((at, e) -> {
            if(e == null && at != null) {
                result.incrementAndGet();
            } else {
                System.out.println("Fail to call omToken.check");
            }
            counter.countDown();
        }, IThreadPool.Pool);
  
        instance.check(req, bkdToken).whenCompleteAsync((at, e) -> {
            if(e == null && at != null) {
                result.incrementAndGet();
            } else {
                System.out.println("Fail to call bkdToken.check");
            }
            counter.countDown();
        }, IThreadPool.Pool);
        counter.await();
        instance.appTokenChecker.setTokenWorker(IConst.SERVICE_OM, old);
        assertEquals(result.get(), 3);
    }
    
    @Test
    public void testSortedAppName() {
        String checker = "APP";
        String services = "a,b,c,d";
        String sortedName = TokenCheckers.sortedName(checker, services);
        assertEquals(sortedName, "APP-A,B,C,D");
        
        checker = "APP";
        services = "b,a,c,d";
        sortedName = TokenCheckers.sortedName(checker, services);
        assertEquals(sortedName, "APP-A,B,C,D");
        
        checker = "APP";
        services = "*";
        sortedName = TokenCheckers.sortedName(checker, services);
        assertEquals(sortedName, "APP-*");
    }
}
