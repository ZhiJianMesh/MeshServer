package cn.net.zhijian.mesh.frm.config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import cn.net.zhijian.mesh.bean.BigFileReader;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.ResponseInfo.DocxResponseHandler;
import cn.net.zhijian.mesh.frm.config.ResponseInfo.JsonResponseHandler;
import cn.net.zhijian.mesh.frm.config.ResponseInfo.TextResponseHandler;
import cn.net.zhijian.mesh.frm.config.ResponseInfo.XlsxResponseHandler;
import cn.net.zhijian.mesh.frm.config.para.ParameterInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.prot.http.HttpServerRequest4Test;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.HttpUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.util.ValParser;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;

public class ResponseInfoTest extends UnitTestBase {
    private static final String DOCX_CFG = "{\n"
        + "        \"type\":\"DOCX\",\n"
        + "        \"template\":\"docxcfg.zip\",\n"
        + "        \"saveAs\":\"DOCX.docx\"\n"
        + "    }";
    private static final String XLSX_CFG = "{\n"
        + "        \"type\":\"XLSX\",\n"
        + "        \"template\":\"xlsxcfg.zip\",\n"
        + "        \"saveAs\":\"XLSX\"\n"
        + "    }";
    private static final String TEXT_CFG = "{\n"
        + "        \"type\":\"TEXT\",\n"
        + "        \"template\":\"textcfg.txt\",\n"
        + "        \"saveAs\":\"TEXT\"\n"
        + "    }";
    private static final String JSON_CFG = "[\n"
            + "{\"name\":\"a\", \"type\":\"int\", \"must\":true, \"min\":0},\n"
            + "{\"name\":\"b\", \"type\":\"string\", \"must\":true, \"min\":1},\n"
            + "{\"name\":\"c\", \"type\":\"datetime\", \"format\":\"yyyy/MM/dd HH:mm\",\"must\":true},\n"
            + "{\"name\":\"list\", \"type\":\"object\", \"list\":true, \"must\":true, \"props\":["
            + " {\"name\":\"a\", \"type\":\"int\", \"must\":true, \"min\":0},\n"
            + " {\"name\":\"b\", \"type\":\"string\", \"must\":true, \"min\":1},\n"
            + " {\"name\":\"c\", \"type\":\"string\", \"must\":true, \"min\":0}\n"
            + "]},\n"
            + "]";
    
    private static AbsServerRequest req;
    private static final Map<String, Object> params = new HashMap<>();
    
    static {
        ArrayList<Map<String, Object>> list = new ArrayList<>();
        list.add(MapBuilder.of("a", 1, "b", "1b", "c", "test1"));
        list.add(MapBuilder.of("a", 2, "b", "2b", "c", "test2"));
        params.put("list", list);
        params.put("a", 1);
        params.put("b", "str");
        params.put("c", System.currentTimeMillis());
        params.put("d", "none");
        req = HttpServerRequest4Test.create(testService, null, params);
    }

    @BeforeAll
    public static void init() {
        File outRoot = new File(OUT_ROOT);
        outRoot.mkdirs();
        File serviceRoot = new File(SERVICE_ROOT);
        serviceRoot.mkdirs();
    }

    @Test
    public void testTextResp() throws InterruptedException {
        TextResponseHandler respHandler = new TextResponseHandler();
        Map<String, Object> cfg = JsonUtil.jsonToMap(TEXT_CFG);
        boolean r = respHandler.parse(testService, params.keySet(), cfg);
        assertTrue(r);

        CountDownLatch counter = new CountDownLatch(1);
        String outfile = FileUtil.addPath(OUT_ROOT, "txt.txt");
        AbsServerResponse resp = new EmptyServerResponse(req, outfile, counter);
        
        respHandler.response(req, resp, params, null, false);
        boolean result = counter.await(3, TimeUnit.SECONDS);
        assertTrue(result);
        assertEquals(resp.headers().get(HttpUtil.HEAD_ATTACHMENT), "attachment;filename=TEXT.txt");
        File f = new File(outfile);
        assertTrue(f.exists());
        String s = FileUtil.readFile(f, IConst.DEFAULT_CHARSET);
        assertTrue(s.startsWith("1\t1b\ttest1"));
        FileUtil.remove(f);
    }
    
    @Test
    public void testDocxResp() throws InterruptedException {
        DocxResponseHandler respHandler = new DocxResponseHandler();
        Map<String, Object> cfg = JsonUtil.jsonToMap(DOCX_CFG);
        boolean r = respHandler.parse(testService, params.keySet(), cfg);
        assertTrue(r);
        
        CountDownLatch counter = new CountDownLatch(1);
        String outfile = FileUtil.addPath(OUT_ROOT, "test.docx");
        AbsServerResponse resp = new EmptyServerResponse(req, outfile, counter);

        respHandler.response(req, resp, params, null, false);
        boolean result = counter.await(3, TimeUnit.SECONDS);
        assertTrue(result);
        assertEquals(resp.headers().get(HttpUtil.HEAD_ATTACHMENT), "attachment;filename=DOCX.docx");
        File f = new File(outfile);
        assertTrue(f.exists());
        FileUtil.remove(f);
    }
    
    @Test
    public void testXlsxResp() throws InterruptedException {
        XlsxResponseHandler respHandler = new XlsxResponseHandler();
        Map<String, Object> cfg = JsonUtil.jsonToMap(XLSX_CFG);
        boolean r = respHandler.parse(testService, params.keySet(), cfg);
        assertTrue(r);
        CountDownLatch counter = new CountDownLatch(1);
        String outfile = FileUtil.addPath(OUT_ROOT, "test.xlsx");
        AbsServerResponse resp = new EmptyServerResponse(req, outfile, counter);

        respHandler.response(req, resp, params, null, false);
        boolean result = counter.await(3, TimeUnit.SECONDS);
        assertTrue(result);
        assertEquals(resp.headers().get(HttpUtil.HEAD_ATTACHMENT), "attachment;filename=XLSX.xlsx");
        File f = new File(outfile);
        assertTrue(f.exists());
        FileUtil.remove(f);
    }
    
    @Test
    public void testJsonResp() throws InterruptedException {
        JsonResponseHandler respHandler = new JsonResponseHandler();
        List<Object> cl = JsonUtil.jsonToList(JSON_CFG);
        ParameterInfo[] pList = new ParameterInfo[cl.size()];
        int i = 0;
        for(Object c : cl) {
            pList[i] = ParameterInfo.parse(ValParser.parseObject(c), true);
            i++;
        }
        CountDownLatch counter = new CountDownLatch(1);
        String outfile = FileUtil.addPath(OUT_ROOT, "test.json");
        AbsServerResponse resp = new EmptyServerResponse(req, outfile, counter);

        respHandler.response(req, resp, params, pList, true);
        boolean result = counter.await(3, TimeUnit.SECONDS);
        assertTrue(result);
        File f = new File(outfile);
        assertTrue(f.exists());
        String s = FileUtil.readFile(f, IConst.DEFAULT_CHARSET);
        Map<String, Object> m = JsonUtil.jsonToMap(s);
        m = ValParser.getAsObject(m, "data");
        int v = ValParser.getAsInt(m, "a");
        assertEquals(v, 1);
        s = ValParser.getAsStr(m, "b");
        assertEquals(s, "str");
        s = ValParser.getAsStr(m, "c");
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy/MM/dd HH:mm");
        assertEquals(s, fmt.format(new Date()));
        List<Object> list = ValParser.getAsList(m, "list");
        assertEquals(list.size(), 2);
        assertTrue(!m.containsKey("d"));
        
        FileUtil.remove(f);
    }
    
    private static class EmptyServerResponse extends AbsServerResponse {
        final String saveAs;
        final CountDownLatch counter;
        protected EmptyServerResponse(AbsServerRequest req, String saveAs, CountDownLatch counter) {
            super(null, req);
            this.saveAs = saveAs;
            this.counter = counter;
        }

        @Override
        public CompletableFuture<Boolean> sendChunkedFile(BigFileReader f) {
            int readLen;
            int LEN = 8192;
            int pos = 0;
            
            try(FileOutputStream out = new FileOutputStream(saveAs)) {
                byte[] buf;
                do {
                    buf = f.read(pos, LEN);
                    readLen = buf.length;
                    pos += readLen;
                    out.write(buf);
                } while(readLen == LEN);
            } catch (IOException e) {
                e.printStackTrace();
            }
            counter.countDown();
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public void end(ByteBuf body, int len) {
            int LEN = body.readableBytes();
            int pos = 0;
            
            try(FileOutputStream out = new FileOutputStream(saveAs)) {
                byte[] buf = new byte[LEN];
                body.getBytes(pos, buf);
                out.write(buf);
            } catch (IOException e) {
                e.printStackTrace();
            }
            counter.countDown();
        }
        
        @Override
        public void end(HandleResult hr) {
            String resultStr = hr.toString();
            byte[] content = resultStr.getBytes(IConst.DEFAULT_CHARSET);
            end(content, 0, content.length);
        }

        @Override
        public void error(HttpResponseStatus statusCode, int resultCode) {
        }
    };
}
