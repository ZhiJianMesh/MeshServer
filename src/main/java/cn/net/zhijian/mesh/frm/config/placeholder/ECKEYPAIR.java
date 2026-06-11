package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * ECC密钥对，可以创建、加解密、签名验签等
 * 如果命令中加了+sec，则需要提供密钥对的密码
 * `@{eckeypair[|cmd,[!]eckeypair,[!]content,[!]sign[,pwd]]}`
 * cmd包括new/public/encode/decode/sign/verify
 * @author flyinmind of csdn.net
 *
 */
final class ECKEYPAIR extends ScriptElement {
    private static final Logger LOG = LogUtil.getInstance();
    private enum KP_ACTION{NEW, PUBLIC, ENCODE, DECODE, SIGN, VERIFY}
    private final KP_ACTION action;
    private final boolean secKp;

    public ECKEYPAIR(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length == 0) {
            action = KP_ACTION.NEW;
            secKp = false;
            return;
        }

        if(ss.length < 2) {
            throw new InvalidParameterException("invalid paramters");
        }
        List<ApiParaHolder> pl = new ArrayList<>();
        try {
            action = KP_ACTION.valueOf(ss[0].toUpperCase());
        } catch(IllegalArgumentException e) {
            throw new InvalidParameterException("invalid action " + ss[0]);
        }
        int idx = 1;
        if(action == KP_ACTION.PUBLIC) {
            pl.add(ApiParaHolder.parse(ss[idx++])); //keypair
        } else if(action != KP_ACTION.NEW) {
            if(ss.length < 3) { //eckeypair|cmd,kp,content,sign,pwd
                throw new InvalidParameterException("invalid paramters,no content");
            }
            pl.add(ApiParaHolder.parse(ss[idx++])); //keypair
            pl.add(ApiParaHolder.parse(ss[idx++])); //content
            if(action == KP_ACTION.VERIFY) {
                if(ss.length < 4) {
                    throw new InvalidParameterException("invalid paramters,no signature");
                }
                pl.add(ApiParaHolder.parse(ss[idx++])); //signature
            }
        }
        if(ss.length > idx) {
            pl.add(ApiParaHolder.parse(ss[idx])); //最后一个是密码
            secKp = true;
        } else {
            secKp = false;
        }
        this.paras = pl.toArray(new ApiParaHolder[] {});
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        try {
            String pwd = null;
            if(secKp) { //安全密钥对的情况，最后一个参数是密钥对的密码
                pwd = ValParser.parseString(paras[paras.length - 1].get(req, resp));
            }
            if(this.action == KP_ACTION.NEW) {
                EccKeyPair kp = Ecc.instance().genKeyPair();
                return pwd == null ? kp.toString() : kp.toString(pwd);
            }
            
            String s = ValParser.parseString(paras[0].get(req, resp));
            EccKeyPair kp = pwd == null ? EccKeyPair.parse(s) : EccKeyPair.parse(s, pwd);
            if(this.action == KP_ACTION.PUBLIC) {
                return kp.publicKey2Str();
            }
            String content = ValParser.parseString(paras[1].get(req, resp));
            if(this.action == KP_ACTION.ENCODE) {
                return kp.encrypt(content);
            }
            if(this.action == KP_ACTION.DECODE) {
                return kp.decrypt(content);
            }
            if(this.action == KP_ACTION.SIGN) {
                return kp.sign(content);
            }
            if(this.action == KP_ACTION.VERIFY) {
                String signature = ValParser.parseString(paras[2].get(req, resp));
                return kp.verify(content, signature);
            }    
        } catch (Exception e) {
            LOG.error("Fail to call eckeypair", e);
        }
        return "";
    }
}
