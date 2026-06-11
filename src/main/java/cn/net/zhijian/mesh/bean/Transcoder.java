package cn.net.zhijian.mesh.bean;

import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 将一个范围内的返回码转换为指定的代码，及响应的info
 * @author flyinmind of csdn.net
 *
 */
public final class Transcoder {
    private static final Logger LOG = LogUtil.getInstance();

    private static final String CFG_START = "start";
    private static final String CFG_END = "end";
    private static final String CFG_CODE = "code";
    private static final String CFG_TO = "to";
    private static final String CFG_INFO = "info";
    private static final String CFG_DATA = "data";

    private final int start;
    private final int end;
    public final int to;
    public final String info;
    public final ScriptElement[] data;

    public Transcoder(int start, int end, int to, String info, ScriptElement[] data) {
        this.start = start;
        this.end = end;
        this.to = to;
        this.info = info;
        this.data = data;
    }

    public boolean in(int code) {
        return code >= start && code <= end;
    }

    public static Transcoder[] parse(List<Object> cfg, Set<String> availableParas) {
        Transcoder[] transcoders = new Transcoder[cfg.size()];
        int no = 0;
        int start;
        int end;
        int to;
        int code;
        String info;
        String dataStr;

        for(Object o : cfg) {
            Map<String, Object> c = ValParser.parseObject(o);
            if(c == null) {
                LOG.error("Fail to parse no.{} transcoder", no);
                return null;
            }

            String sCode = ValParser.getAsStr(c, CFG_CODE);
            if(!StringUtil.isEmpty(sCode)) { //设置code时，可以使用名称
                if(sCode.equalsIgnoreCase("all")) {
                    start = 0;
                    end = Integer.MAX_VALUE;
                } else {
                    code = RetCode.parseCode(sCode, RetCode.OK);
                    start = end = code;
                }
            } else {
                start = ValParser.getAsInt(c, CFG_START);
                end = ValParser.getAsInt(c, CFG_END);
            }
            if(start > end) {
                LOG.error("Fail to parse no.{} transcoder, start({})>end({})", no, start, end);
                return null;
            }

            to = RetCode.parseCode(c.get(CFG_TO), -1);
            if(to < 0) {
                LOG.error("Fail to parse no.{} transcoder, invalid to({})", no, to);
                return null;
            }

            info = ValParser.getAsStr(c, CFG_INFO);
            if(StringUtil.isEmpty(info)) {
                info = RetCode.getInfo(to);
            }

            ScriptElement[] data = null;
            if(to == RetCode.OK) {
                dataStr = ValParser.getAsStr(c, CFG_DATA);
                if(!StringUtil.isEmpty(dataStr)) {
                    data = ScriptElement.parsePlaceHolder(info, availableParas, "\"", "\\\"");
                }
            }

            transcoders[no] = new Transcoder(start, end, to, info, data);
            no++;
        }
        return transcoders;
    }
}
