package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.security.SecureRandom;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.bean.Relation;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * `@{RANDOM|l/d/f/c/s},@{RANDOM|i,min,max]},@{RANDOM|s,len,base]}`
 * @author flyinmind of csdn.net
 *
 */
final class RANDOM extends ScriptElement {
    private int max, min;
    private final int type;

    public RANDOM(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length == 0) {
            this.type = Relation.TYPE_INT;
            min = Integer.MIN_VALUE;
            max = Integer.MAX_VALUE;
            return;
        }

        this.type = Relation.parseType(ss[0]);
        if(this.type == Relation.TYPE_CHAR) {
            min = ss.length > 1 ? ApiParaHolder.takeStr(ss[1]).charAt(0) : Character.MIN_VALUE;
            max = ss.length > 2 ? ApiParaHolder.takeStr(ss[2]).charAt(0) : Character.MAX_VALUE;
        } else if(this.type == Relation.TYPE_INT) { //i,min,max 最大不超过max，不小于min
            min = ss.length > 1 ? Integer.parseInt(ApiParaHolder.takeStr(ss[1])) : 0;
            max = ss.length > 2 ? Integer.parseInt(ApiParaHolder.takeStr(ss[2])) : Integer.MAX_VALUE;
        } else if(this.type == Relation.TYPE_STRING) { //example:s,100|s,23,63|32
            min = ss.length > 1 ? ValParser.parseInt(ApiParaHolder.takeStr(ss[1]), 10) : 10; //len
            max = ss.length > 2 ? ValParser.parseInt(ApiParaHolder.takeStr(ss[2]), 64) : 64; //base,16:HEX,32:BASE32,64:BASE64
            if(max != 16 && max != 32 && max != 64) {
                throw new InvalidParameterException("invalid string base number");
            }
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        SecureRandom rand = SecureUtil.getRandom();
        switch(this.type) {
            case Relation.TYPE_LONG: return rand.nextLong();
            case Relation.TYPE_INT: return rand.nextInt(this.max - this.min) + this.min;
            case Relation.TYPE_DOUBLE: return rand.nextDouble();
            case Relation.TYPE_FLOAT: return rand.nextFloat();
            case Relation.TYPE_CHAR: return (char)(rand.nextInt(this.max - this.min) + this.min);
            default: { //TYPE_STR
                char[] buf = new char[this.min];
                if(this.max == 32) { //base32
                    for(int i = 0; i < this.min; i++) {
                        buf[i] = ByteUtil.getBase32CharByVal(rand.nextInt(max));
                    }
                } else if(this.max == 16) { //hex
                    for(int i = 0; i < this.min; i++) {
                        buf[i] = ByteUtil.getHexCharByVal(rand.nextInt(this.max));
                    }
                } else { //base64
                    for(int i = 0; i < this.min; i++) {
                        buf[i] = ByteUtil.getBase64CharByVal(rand.nextInt(64));
                    }
                }
                return new String(buf);
            }
        }
    }
}