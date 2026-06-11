package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * `@{PBKDF|[iteractionCount,]paraName[,...]}`，比HMACSHA256强度高
 * 如果需要检验输入字符串与保存的字符串是否一致，
 * 可以在js中调用Secure.pbkdf2Check(s,saved)
 * @author flyinmind of csdn.net
 *
 */
final class PBKDF extends ScriptElement {
    private final int iterationCount;

    public PBKDF(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 1) {
            throw new InvalidParameterException("invalid PBKDF config");
        }
        
        if(ss.length >= 2) { //两个以上参数的情况，第一个为迭代次数
            int count = ValParser.parseInt(ss[0], 0);
            int start = 0;
            //第一个参数如果不是数字，则当普通参数处理
            if(count <= 0) {
                count = 2;
            } else { //否则当做迭代次数处理
                start = 1;
                if(count > 32) {
                    count = 16;
                }
            }
            this.paras = new ApiParaHolder[ss.length - start];
            for(int i = start; i < ss.length; i++) {
                this.paras[i - start] = ApiParaHolder.parse(ss[i]);
            }
            this.iterationCount = count;
        } else { //否则第一个是参数，迭代次数默认是2
            this.iterationCount = 2;
            this.paras = ApiParaHolder.parseHolders(ss[0]);
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String s = concatStr(req, resp, paras);
        return SecureUtil.pbkdf2(s, this.iterationCount);
    }
}
