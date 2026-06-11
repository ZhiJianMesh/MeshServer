package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.SecureUtil;

/**
 * `@{HMACSHA256|paraName,...}`
 * 如果需要检验输入字符串与保存的字符串是否一致，
 * 可以在js中调用Secure.hmacSHA256Check(s, saved)
 * @author flyinmind of csdn.net
 *
 */
final class HMACSHA256 extends ScriptElement {
    public HMACSHA256(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        this.paras = ApiParaHolder.parseHolders(paras);
        if(this.paras == null || this.paras.length < 1) {
            throw new InvalidParameterException("invalid HMACSHA256 config");
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String s = concatStr(req, resp, paras);
        byte[] cipher = SecureUtil.hmacSHA256(s.getBytes(IConst.DEFAULT_CHARSET));
        return ByteUtil.bin2base64(cipher);
    }
}
