package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.Relation;
import cn.net.zhijian.mesh.client.SequenceClient;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo.ServiceType;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 序列ID
 * `@{SEQUENCE|[i|int|l|long,]idName[,len,[,cidPara]]}`
 * @author flyinmind of csdn.net
 *
 */
final class SEQUENCE extends ScriptElement {
    private final String keyName;
    private final ApiParaHolder cidPara;
    private final int seqType;
    private final int len;

    public SEQUENCE(String paras, ScriptElement.EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 1) {
            throw new InvalidParameterException("invalid SEQUENCE config");
        }
        int len = 0;
        int seqType = Relation.TYPE_INT; //int/long
        ApiParaHolder cidPara = null;
        
        String s = ApiParaHolder.takeStr(ss[0]);
        int paraNum = 2; //至少有两个参数
        int tp = Relation.parseType(s); //第一个不一定是类型参数
        if(tp == Relation.TYPE_LONG) {
            seqType = Relation.TYPE_LONG;
            paraNum = 3;
            if(ss.length < 2) {
                throw new InvalidParameterException("invalid SEQUENCE config");
            }
            s = ss[1];
        } else if(tp == Relation.TYPE_INT) {
            paraNum = 3;
            if(ss.length < 2) {
                throw new InvalidParameterException("invalid SEQUENCE config");
            }
            s = ss[1];
        }
        this.seqType = seqType;
        this.keyName = ApiParaHolder.takeStr(s);//可以加引号，也可以不加
        if(ss.length >= paraNum) { //[type,]keyName,[len],cidParaName
            s = ss[paraNum - 1];
            if(s.matches("\\d+")) {
                len = Integer.parseInt(s);
                if(ss.length > paraNum) {
                    cidPara = ApiParaHolder.parse(ss[paraNum]);
                }
            } else {
                cidPara = ApiParaHolder.parse(ss[paraNum - 1]);
            }
        }
        this.len = len;
        this.cidPara = cidPara;        
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        int cid;
        ServiceInfo si = req.serviceInfo();
        if(this.cidPara != null) {
            cid = ValParser.parseInt(cidPara.get(req, resp), 0);
        } else {
            cid = si.type == ServiceType.COMPANY ? req.cid() : CompanyInfo.instance().id;
        }

        if(this.len <= 0) { //不格式化
            if(this.seqType == Relation.TYPE_LONG) {
                return SequenceClient.nextId(cid, si, this.keyName, req.traceId);
            }
            return SequenceClient.nextIntId(cid, si, this.keyName, req.traceId);
        }
        
        if(this.seqType == Relation.TYPE_LONG) {
            long v = SequenceClient.nextId(cid, si, this.keyName, req.traceId);
            return formatVal(v, this.len);
        }
        int v = SequenceClient.nextIntId(cid, si, this.keyName, req.traceId);
        return formatVal(v, this.len);
    }
    
    private static String formatVal(long v, int len) {
        String s;
        char[] cl = new char[len]; //不足len的，前面填0，超过的截断
        for(int i = len - 1; i >= 0; i--) {
            cl[i] = (char)('0' + ((int)(v % 10)));
            v /= 10;
        }
        s = new String(cl);
        return s;
    }
}
