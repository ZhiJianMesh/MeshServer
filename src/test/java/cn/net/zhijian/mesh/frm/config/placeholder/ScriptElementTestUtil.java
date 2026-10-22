package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.HashMap;
import java.util.Map;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.prot.http.HttpServerRequest4Test;

class ScriptElementTestUtil extends UnitTestBase {
    protected static  String segementsToStr(Map<String, String> headers, Map<String, Object> params, Map<String, Object> resp, ScriptElement[] segs) {
        StringBuilder sb = new StringBuilder();
        AbsServerRequest req = HttpServerRequest4Test.create(testService, headers, params);
        for(ScriptElement se : segs) {
            sb.append(se.run(req, resp));
        }
        String str = sb.toString();
        System.out.println(str);
        return str;
    }

    protected String segementsToStr(Map<String, Object> params, Map<String, Object> resp, ScriptElement[] segs) {
        return segementsToStr(new HashMap<>(), params, resp, segs);
    }

}
