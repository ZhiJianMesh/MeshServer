package cn.net.zhijian.mesh.frm.config.placeholder;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.bean.Relation;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.PartitionConfig;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.StringUtil;

/**
 * `@{CONST|type,name(min|max|ver|tzoffset)}`
 * @author flyinmind of csdn.net
 *
 */
final class CONST extends ScriptElement {
    private enum CONST_NAME {MAX, MIN, VER, TZOffset}
    private final int VERSION_INT = StringUtil.verToInt(IConst.ENGINEVERSION);

    private final CONST_NAME name;
    private final int valType;

    public CONST(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 2) {
            throw new InvalidParameterException("invalid NUMBER config");
        }
        this.valType = Relation.parseType(ss[0]);
        String name = ss[1].toLowerCase();
        if(name.equals("ver")) {
            if(this.valType != Relation.TYPE_INT
               && this.valType != Relation.TYPE_STRING) {
                throw new InvalidParameterException("invalid ver type");
            }
            this.name = CONST_NAME.VER;
        } else if(name.equals("tzoffset")) {
            if(this.valType != Relation.TYPE_INT) {
                 throw new InvalidParameterException("invalid tzoffset type");
             }
            this.name = CONST_NAME.TZOffset;
        } else {
            if(!Relation.isNumber(this.valType) || this.valType == Relation.TYPE_CHAR) {
                throw new InvalidParameterException("invalid value type");
            }
            if(name.equals("min")) {
                this.name = CONST_NAME.MIN;
            } else if(name.equals("max")){
                this.name = CONST_NAME.MAX;
            } else {
                throw new InvalidParameterException("invalid name");
            }
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        if(this.name == CONST_NAME.MAX) {
            switch(this.valType) {
                case Relation.TYPE_INT:
                    return Integer.MAX_VALUE;
                case Relation.TYPE_LONG:
                    return Long.MAX_VALUE;
                case Relation.TYPE_FLOAT:
                    return Float.MAX_VALUE;
                case Relation.TYPE_CHAR:
                    return Character.MAX_VALUE;
                default://case Relation.TYPE_DOUBLE:
                    return Double.MAX_VALUE;
            }
        } else if(this.name == CONST_NAME.MIN) {
            switch(this.valType) {
            case Relation.TYPE_INT:
                return Integer.MIN_VALUE;
            case Relation.TYPE_LONG:
                return Long.MIN_VALUE;
            case Relation.TYPE_FLOAT:
                return Float.MIN_VALUE;
            case Relation.TYPE_CHAR:
                return Character.MIN_VALUE;
            default://case Relation.TYPE_DOUBLE:
                return Double.MIN_VALUE;
            }
        } else if(this.name == CONST_NAME.VER){
            if(this.valType == Relation.TYPE_INT) {
                  return VERSION_INT;
            } else {/*Relation.TYPE_STRING*/
                return IConst.ENGINEVERSION;
            }
        } else {
            return PartitionConfig.instance().timeZone;//时区偏移的分钟数
        }
    }
}
