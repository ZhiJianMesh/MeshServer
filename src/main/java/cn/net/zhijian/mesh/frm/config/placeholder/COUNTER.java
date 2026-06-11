package cn.net.zhijian.mesh.frm.config.placeholder;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.StringUtil;

/**
 * 服务类计数器，同一个实例内，同一个服务，保证递增，到顶后归零，继续递增
 * 输出由len指定位数的一个数字，如果指定了header，会携带header一起返回
 * len默认为4，header默认为空字符串
 * `@{COUNTER[|len[,header]]}`
 * @author flyinmind of csdn.net
 *
 */
final class COUNTER extends ScriptElement {
    private final int len;
    private static final Map<String, AtomicLong> Counters = new HashMap<>(); 

    public COUNTER(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        int len = 0;
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length > 0) {
            if(ss[0].matches("\\d+")) { //len or head_para
                len = Integer.parseInt(ss[0]);
                if(ss.length > 1) {
                    this.paras = new ApiParaHolder[] {ApiParaHolder.parse(ss[1])};
                }
            } else { //head_para
                this.paras = new ApiParaHolder[] {ApiParaHolder.parse(ss[0])};
            }
        }
        this.len = len;
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        AtomicLong counter = Counters.get(req.serviceInfo().name);
        if(counter == null) {
            counter = new AtomicLong(0);
            Counters.put(req.serviceInfo().name, counter);
        }
        long c = counter.getAndIncrement();
        String s;
        if(this.len > 0) {
            char[] cl = new char[this.len];
            for(int i = this.len - 1; i >= 0; i--) {
                cl[i] = (char)('0' + ((int)(c % 10)));
                c /= 10;
            }
            s = new String(cl);
        } else {
            s = "" + c;
        }
        if(this.paras == null) {
            return s;
        }
        
        return this.paras[0].getAsString(req, resp) + s; //head+number
    }
}
