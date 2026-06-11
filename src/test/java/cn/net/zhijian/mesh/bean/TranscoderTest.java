package cn.net.zhijian.mesh.bean;

import static cn.net.zhijian.UnitTestBase.assertEquals;
import static cn.net.zhijian.UnitTestBase.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.util.JsonUtil;

public class TranscoderTest {
    @Test
    public void testTranscoder() {
        String json = "[{start:2001,end:2010,to:1000},{code:2001,to:100},{code:\"all\",to:100}]";
        List<Object> cfgList = JsonUtil.jsonToList(json);
        Transcoder[] tcs = Transcoder.parse(cfgList, new HashSet<>());
        assertTrue(tcs[0].in(2009));
        assertEquals(tcs[0].to, 1000);
        assertTrue(!tcs[0].in(2011));
        assertTrue(tcs[1].in(2001));
        assertEquals(tcs[1].to, 100);
        assertTrue(tcs[2].in(29999));
        assertEquals(tcs[2].to, 100);
    }
}
