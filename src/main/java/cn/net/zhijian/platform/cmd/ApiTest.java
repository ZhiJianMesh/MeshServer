package cn.net.zhijian.platform.cmd;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.HttpClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.HttpUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ApiTest extends AbsCommand {
    private static final Logger LOG = LogUtil.getInstance();
    
    private static final String PARAMETERS = "parameters";
    private static final String HEADERS = "headers";
    
    private static final String HOLDER_REQNO = "#reqNo";
    private static final String HOLDER_THREADNO = "#threadNo";
    private static final String HOLDER_CURTIME = "#curTime";
    private static final MediaType MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    
    private static final String HOST = "host";
    //private static final String TIMEOUT = "timeout";
    private static final String CONCURRENT = "concurrent";
    
    private static final String UC_LIST = "usecases";
    
    private static final String UC_NAME = "name";
    private static final String UC_MESSAGE = "message"; //请求消息
    private static final String UC_URL = "url";
    private static final String UC_METHOD = "method";
    private static final String UC_SUCCESS = "success"; //用于检查响应是否成功的标准
    private static final String UC_REPEAT = "repeat";
    private static final String UC_EXPECTCODE = "expectCode"; //期望的返回码
    private static final String UC_HEADERS = "headers";

    private static final String METHOD_GET = "GET";
    private static final String METHOD_POST = "POST";
    private static final String METHOD_PUT = "PUT";
    private static final String METHOD_DELETE = "DELETE";
    
    private static final String WORKDIR = System.getProperty("user.dir");
    
    private static HttpUtil http;
    
    private final Map<String, ApiData> apiInfos = new ConcurrentHashMap<>();
    private String host = "http://localhost:8080";
    
    private Map<String, Object> parameters = null;
    private Map<String, String> headers = null;
    
    static {
        try {
            http = HttpClient.getHttpClient(5000);
        } catch (Exception e) {
            LOG.error("Fail to create root trustmanager", e);
            http = null;
        }
    }    

    public ApiTest(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        startTest(args);
        return true;
    }

    @Override
    public String[] help() {
        return new String[] {
            "test apis in bulk,support simple logics",
            name + " [concurrent] [path_to_test_file]",
            "default path_to_test_file is usecase/test.cfg under working directroy"
        };
    }

    private boolean startTest(String[] args) throws InterruptedException {
        String usecaseFile = FileUtil.addPath(WORKDIR, "usecase", "test.cfg");
        int concurrent = -1;
        if(args.length > 0) {
            concurrent = ValParser.parseInt(args[0], -1);
            if(concurrent < 0) {
                usecaseFile = args[0];
            } else if(args.length > 1) {
                usecaseFile = args[1];
            }
        }
        File ucf = new File(usecaseFile);
        if(!ucf.exists()) {
            LOG.error("File not exists:{}", usecaseFile);
            return false;
        }
        
        LOG.info("Start execute use cases in:{}", usecaseFile);
        Map<String, Object> cfg = JsonUtil.jsonFileToMap(ucf, true);
        if(cfg == null) {
            LOG.error("Fail to parse config file:{}", usecaseFile);
            return false;
        }
        if(concurrent <= 0) { //传了参数，则直接使用参数的值
            concurrent = ValParser.getAsInt(cfg, CONCURRENT, 1);
        }
        host = ValParser.getAsStr(cfg, HOST, null);
        if(StringUtil.isEmpty(host)) {
            LOG.error("Invalid {} config item", HOST);
            return false;
        }
        
        parameters = ValParser.getAsObject(cfg, PARAMETERS);
        Map<String, Object> hh = ValParser.getAsObject(cfg, HEADERS);
        if(hh != null && hh.size() > 0) {
            headers = new HashMap<String, String>();
            for(Map.Entry<String, Object> h : hh.entrySet()) {
                headers.put(h.getKey(), ValParser.parseString(h.getValue()));
            }
        }
        
        List<Object> ucList = ValParser.getAsList(cfg, UC_LIST);
        CountDownLatch counter = new CountDownLatch(concurrent);
        long t0 = System.currentTimeMillis();
        
        for(int i = 0; i < concurrent; i++) {
            final int threadNo = i;
            new Thread() {
                public void run() {
                    execute(ucList, threadNo);
                    counter.countDown();
                }
            }.start(); //每一个都是顺序、同步执行
        }
        
        counter.await();
        int requestNum = 0;
        int successNum = 0;
        int errorNum = 0;
        int failedNum = 0;
        for(Map.Entry<String, ApiData> l : apiInfos.entrySet()) {
            ApiData ad = l.getValue();
            requestNum += ad.requestNum.get();
            successNum += ad.successNum.get();
            errorNum += ad.errorNum.get();
            failedNum += ad.failedNum.get();
            LOG.info("{}==request:{},success:{},error:{},failed:{},interval(ms):ave-{},max-{},min-{}", ad.url,
                    ad.requestNum.get(), ad.successNum.get(), ad.errorNum.get(), ad.failedNum.get(),
                    ad.interval.get() / ad.requestNum.get(), ad.maxInterval.get(), ad.minInterval.get());
        }
        long t = System.currentTimeMillis() - t0;
        LOG.info("Summary==request:{},success:{},error:{},failed:{},time(ms):{},speed:{}",
                 requestNum, successNum, errorNum, failedNum,
                 t, (1000L * requestNum / t));
        return true;
    }
    
    private void execute(List<Object> arr, int threadNo) {
        int i, size = arr.size();
        
        Map<String, Object> runtimeData = new HashMap<String, Object>();
        if(parameters != null) {
            runtimeData.putAll(parameters);
        }
        runtimeData.put(HOLDER_THREADNO, threadNo);
        for(i = 0; i < size; i++) {
            try {
                executeOne(ValParser.getAsObject(arr, i), runtimeData);
            } catch (IOException e) {
                LOG.error("Fail to execute no.{}", i, e);
            }
        }
    }
    
    private void executeOne(Map<String, Object> uc, Map<String, Object> runtimeData) throws IOException {
        String name = ValParser.getAsStr(uc, UC_NAME, "Unknown");
        String cfgUrl = ValParser.getAsStr(uc, UC_URL);
        if(StringUtil.isEmpty(cfgUrl)) {
            LOG.warn("No url to exeucte in {}", name);
            return;
        }

        ApiData ad = apiInfos.get(cfgUrl);
        if(ad == null) {
            ad = new ApiData(cfgUrl);
            apiInfos.put(cfgUrl, ad);
        }
        
        Map<String, Object> cfgBody = ValParser.getAsObject(uc, UC_MESSAGE);
        Map<String, String> cfgHeaders = ValParser.getAsStrMap(uc, UC_HEADERS);
        Map<String, Object> success = ValParser.getAsObject(uc, UC_SUCCESS);

        int repeat = ValParser.getAsInt(uc, UC_REPEAT, 1);
        int expectedCode = ValParser.getAsInt(uc, UC_EXPECTCODE, RetCode.OK);
        String method = ValParser.getAsStr(uc, UC_METHOD, METHOD_POST).toUpperCase();
        
        long t0 = System.currentTimeMillis();
        long t1;
        int retCode;
        int t, no;
        boolean checkOk;
        String body, url;
        Map<String, Object> respJson, data;
        Map<String, String> headers = null;
        Map<String, Object> nullData = Map.of();
        
        for(int i = 0; i < repeat; i++) {
            no = ad.requestNum.incrementAndGet();
            t1 = System.currentTimeMillis();
            runtimeData.put(HOLDER_REQNO, no);
            runtimeData.put(HOLDER_CURTIME, t1);
            
            url = replaceHolders(cfgUrl, runtimeData, no);
            headers = new HashMap<String, String>();
            if(cfgHeaders != null) {
                headers.putAll(cfgHeaders);
            }
            if(this.headers != null) {
                headers.putAll(this.headers);
            }        
            
            Request.Builder builder = null;
            if(method.equals(METHOD_GET) || method.equals(METHOD_DELETE)) {
                builder = http.buildRequest(method, host + url, headers, null);
            } else if(method.equals(METHOD_POST) || method.equals(METHOD_PUT)) {
                body = replaceHolders(JsonUtil.objToJson(cfgBody), runtimeData, no);
                builder = http.buildRequest(method, host + url, headers,
                        RequestBody.create(MEDIA_TYPE, body));
            } else {
                LOG.error("Invalid method:{}", method);
                continue;
            }
            
            try(Response resp = http.syncRequest(builder.build())) {
                if(resp == null) {
                    LOG.error("Failed, get a null response, or wrong status code:{}");
                    continue;
                }
                
                if(!resp.isSuccessful()) {
                    ad.failedNum.incrementAndGet();
                    LOG.error("Wrong status code:{}", resp.code());
                    continue;
                }
                
                try(ResponseBody respBody = resp.body()) {
                    body = respBody.string();
                    respJson = JsonUtil.jsonToMap(body);
                    if(respJson == null || respJson.size() == 0) {
                        LOG.error("Fail to parse body from response:{}", body);
                        continue;
                    }
                }
                
                retCode = ValParser.getAsInt(respJson, HandleResult.CODE, -1);
                checkOk = expectedCode == retCode;
                if(retCode == RetCode.OK) {
                    if((data = ValParser.getAsObject(respJson, HandleResult.DATA)) != null) {
                        runtimeData.putAll(data);
                    } else {
                        data = nullData;
                    }
                    
                    if(success != null) {
                        Object s, v;
                        String k;
                        
                        for(Map.Entry<String, Object> o : success.entrySet()) {
                            k = o.getKey();
                            s = o.getValue();
                            v = data.get(k);
                            if(v == null || !v.equals(s)) {
                                LOG.error("{} => {},expect {}", k, v, s);
                                checkOk = false;
                            }
                        }
                    }
                }
                if(checkOk) {
                    ad.successNum.incrementAndGet();
                } else {
                    ad.errorNum.incrementAndGet();
                }
            } catch(Exception e) {
                LOG.error("Fail to request {}", url, e);
                ad.failedNum.incrementAndGet();
            } finally {
                t = (int)(System.currentTimeMillis() - t1);
                if(t > ad.maxInterval.get()) {
                    ad.maxInterval.set(t);
                }
                
                if(t < ad.minInterval.get()) {
                    ad.minInterval.set(t);
                }
            }
        }
        t = (int)(System.currentTimeMillis() - t0);
        ad.interval.addAndGet(t);
    }
    
    private String replaceHolders(String s, Map<String, Object> data, int no) {
        int pos = 0, end = 0;
        String name;
        Object val;
        int len = s.length();
        StringBuilder sb = new StringBuilder(2 * len);
        
        while((pos = s.indexOf("@{", end)) > 0) {
            if(pos > end) {
                sb.append(s.substring(end, pos));
            }
            pos += 2;
            end = s.indexOf('}', pos);
            if(end <= 0) {
                end = pos;
                break;
            }
            name = s.substring(pos, end);
            if(HOLDER_REQNO.equals(name)) {
                sb.append(no);
            } else {
                val = data.get(name);
                if(val instanceof String || val instanceof Number || val instanceof Boolean) {
                    sb.append(val);
                } else {
                    sb.append(JsonUtil.objToJson(val));
                }
            }
            end += 1;
        }
        if(end < len - 1) {
            sb.append(s.substring(end));
        }
        return sb.toString();
    }
    
    private class ApiData {
        public final String url;
        public final AtomicLong interval = new AtomicLong(0);
        public final AtomicInteger requestNum = new AtomicInteger(0);
        public final AtomicInteger successNum = new AtomicInteger(0);
        public final AtomicInteger errorNum = new AtomicInteger(0);
        public final AtomicInteger failedNum = new AtomicInteger(0); //发生http错误
        public final AtomicInteger maxInterval = new AtomicInteger(0);
        public final AtomicInteger minInterval = new AtomicInteger(Integer.MAX_VALUE);
        
        public ApiData(String url) {
            this.url = url;
        }
    }
}
