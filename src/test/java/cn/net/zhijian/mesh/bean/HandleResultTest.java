package cn.net.zhijian.mesh.bean;

import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.ValParser;

public class HandleResultTest extends UnitTestBase {
    @Test
    public void testToString() {
        HandleResult hr = new HandleResult(1, "test");
        String s = hr.toString();
        Map<String, Object> map = JsonUtil.jsonToMap(s);
        int code = ValParser.getAsInt(map, HandleResult.CODE);
        String info = ValParser.getAsStr(map, HandleResult.INFO);
        assertEquals(code, 1);
        assertEquals(info, "test");
        Map<String, Object> d = MapBuilder.of("a", 1, "b", "test");
        hr = new HandleResult(d);
        assertEquals(hr.code, RetCode.OK);
        assertEquals(hr.data.get("a"), 1);
        assertEquals(hr.data.get("b"), "test");
    }
    
    @Test
    public void testTryParse() {
        String json = "{code:1,info:\"test\"}";
        HandleResult hr1 = HandleResult.tryParse(json);
        assertEquals(hr1.code, 1);
        assertEquals(hr1.info, "test");
        HandleResult hr2 = HandleResult.fromStr(json);
        assertEquals(hr2.code, 1);
        assertEquals(hr2.info, "test");
        json = "{info:\"test\"}";
        hr1 = HandleResult.tryParse(json);
        assertEquals(hr1.code, RetCode.OK);
        assertEquals(hr1.info, RetCode.INFO_SUCCESS);
        assertTrue(hr1.data.containsKey("info"));
        hr2 = HandleResult.fromStr(json);
        assertEquals(hr2.code, RetCode.UNKNOWN_ERROR);
    }
}
