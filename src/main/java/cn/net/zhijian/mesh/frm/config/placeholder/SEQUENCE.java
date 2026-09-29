package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.TV;
import cn.net.zhijian.mesh.client.SequenceClient;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo.ServiceType;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 序列ID
 * `@{SEQUENCE|[i|int|l|long,]idName[,num,[,cidPara]]}`
 * @author flyinmind of csdn.net
 *
 */
final class SEQUENCE extends ScriptElement {
    private final String keyName;
    private final ApiParaHolder cidPara;
    private final int seqType;
    private final ApiParaHolder num; //一次申请多少个连续的序列值，默认1

    public SEQUENCE(String paras, ScriptElement.EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 1) {
            throw new InvalidParameterException("invalid SEQUENCE config");
        }
        ApiParaHolder num; //默认申请几个id
        ApiParaHolder cidPara = null;
        int seqType = TV.TYPE_INT; //int/long
        
        String s = ApiParaHolder.takeStr(ss[0]);
        int paraNum = 2; //至少有两个参数
        int tp = TV.parseType(s); //第一个不一定是类型参数
        if(tp == TV.TYPE_LONG) {
            seqType = TV.TYPE_LONG;
            paraNum++;
            if(ss.length < 2) {
                throw new InvalidParameterException("invalid SEQUENCE config");
            }
            s = ss[1];
        } else if(tp == TV.TYPE_INT) {
            paraNum++;
            if(ss.length < 2) {
                throw new InvalidParameterException("invalid SEQUENCE config");
            }
            s = ss[1];
        }
        this.seqType = seqType;
        this.keyName = ApiParaHolder.takeStr(s);//可以加引号，也可以不加
        if(ss.length >= paraNum) { //[type,]keyName,[len],cidParaName
            s = ss[paraNum - 1];
            num = ApiParaHolder.parse(s);
            if(ss.length > paraNum) {
                cidPara = ApiParaHolder.parse(ss[paraNum]);
            }
        } else {
            num = ApiParaHolder.parse("1");
        }
        this.num = num;
        this.cidPara = cidPara;        
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        int cid;
        ServiceInfo si = req.serviceInfo();
        if(this.cidPara != null) {
            cid = ValParser.parseInt(this.cidPara.get(req, resp), 0);
        } else {
            cid = si.type == ServiceType.COMPANY ? req.cid() : CompanyInfo.instance().id;
        }

        Object o = this.num.get(req, resp);
        int num = ValParser.parseInt(o, 1);
        if(this.seqType == TV.TYPE_LONG) {
            return SequenceClient.nextId(cid, si, this.keyName, num, req.traceId);
        }
        return SequenceClient.nextIntId(cid, si, this.keyName, num, req.traceId);
    }
}
