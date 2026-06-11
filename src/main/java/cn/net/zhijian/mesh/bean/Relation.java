package cn.net.zhijian.mesh.bean;

import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.ValParser;

public class Relation {
    public static final int TYPE_INVALID = 0;
    public static final int TYPE_INT = 1;
    public static final int TYPE_LONG = 2;
    public static final int TYPE_FLOAT = 3;
    public static final int TYPE_DOUBLE = 4;
    public static final int TYPE_STRING = 5;
    public static final int TYPE_OBJECT = 6;
    public static final int TYPE_BOOL = 7;
    public static final int TYPE_CHAR = 8;
    
    private static final int NULL = 0x00;
    private static final int EQUAL = 0x10;
    
    private static final int NOTEQUAL = 0x01;
    private static final int BIGGER = 0x02;
    private static final int SMALLER = 0x03;
    
    private static final int MATCH = 0x04;
    private static final int NOTMATCH = 0x05;
    //各元素用逗号分隔，所以元素中不能包含逗号
    //即使是字符串，也不能打引号或单引号，所以字符串中也不能包含逗号
    private static final int IN = 0x06;
    private static final int NOTIN = 0x07;

    private static final int MASK = 0x0F;

    private final int valType; //0:int,1:long,2:float,3:double,4:string
    private final int relation;
    private final ApiParaHolder p1;
    private final ApiParaHolder p2;
    
    public Relation(String cfg, ApiParaHolder p1, ApiParaHolder p2) { //if,else if
        String s = ApiParaHolder.takeStr(cfg).trim(); //如果有引号，则去除
        int r = parseRelation(s);
        this.valType = r >>> 16;
        this.relation = r & 0xffff;
        this.p1 = p1;
        this.p2 = p2;
    }
    
    public Relation() { //else
        this.relation = NULL;
        this.valType = TYPE_STRING;
        this.p1 = null;
        this.p2 = null;
    }
    
    public boolean compare(AbsServerRequest req, Map<String, Object> resp) {
        if(relation == NULL) {
            return true;
        }

        Object o1 = p1.get(req, resp);
        Object o2 = p2.get(req, resp);
        return compare(o1, o2, valType, relation);
    }
    
    public static boolean compare(Object o1, Object o2, int valType, int relation) {
        if(relation == NULL) {
            return true;
        }

        if(relation == IN || relation == NOTIN) {
            String s1 = ValParser.parseString(o1);
            String s2 = ValParser.parseString(o2);
            int idx = s2.indexOf(s1);
            if(idx < 0) {
                return relation == NOTIN;
            }
            int end = idx + s1.length();
            if(end < s2.length()) {
                if(s2.charAt(end) != ',') { //不在结尾，则后面必须是逗号
                    return relation == NOTIN;
                }
            }
            if(idx == 0 || s2.charAt(idx - 1) == ',') {
                return relation == IN; //前面必须是逗号，或是开头
            }
            return relation == NOTIN;
         }
        if(valType == TYPE_INT) {
            int v1 = ValParser.parseInt(o1, 0);
            int v2 = ValParser.parseInt(o2, 0);
            return compare(v1, v2, relation);
        }

        if(valType == TYPE_LONG) {
            long v1 = ValParser.parseLong(o1, 0);
            long v2 = ValParser.parseLong(o2, 0);
            return compare(v1, v2, relation);
        }

        if(valType == TYPE_FLOAT) {
            float v1 = ValParser.parseFloat(o1, 0);
            float v2 = ValParser.parseFloat(o2, 0);
            return compare(v1, v2, relation);
        }

        if(valType == TYPE_DOUBLE) {
            double v1 = ValParser.parseDouble(o1, 0);
            double v2 = ValParser.parseDouble(o2, 0);
            return compare(v1, v2, relation);
        }
        
        if(valType == TYPE_CHAR) {
            char v1 = ValParser.parseChar(o1, ' ');
            char v2 = ValParser.parseChar(o2, ' ');
            return compare(v1, v2, relation);
        }
        
        if(valType == TYPE_STRING) {
            String v1 = ValParser.parseString(o1);
            String v2 = ValParser.parseString(o2);
            if(relation == MATCH) {
                return v1.matches(v2);
            } else if(relation == NOTMATCH) {
                return !v1.matches(v2);
            }
            return compare(v1, v2, relation);
        }
        
        if(valType == TYPE_BOOL) {
            boolean v1 = ValParser.parseBool(o1, true);
            boolean v2 = ValParser.parseBool(o2, true);
            if(relation == EQUAL) {
                return v1 == v2;
            }
            return v1 != v2;
        }
        
        //判断对象是否相等，对象可以为null
        if(o1 == null) {
            if(o2 == null) {
                return relation == EQUAL;
            }
            return relation == NOTEQUAL;
        }
        if(o1.equals(o2)) { //只要o1不为空，o2为空时也可以判断是否相等
            return relation == EQUAL;
        }
        return relation == NOTEQUAL;
    }
    
    public static int valType(int r) {
        return r >>> 16;
    }
    
    public static int relation(int r) {
        return r & 0xffff;
    }
    
    public static int parseRelation(String rs) {
        int pos = rs.indexOf('.');
        String segType = pos <= 0 ? "s" : rs.substring(0, pos);
        int valType = parseType(segType);

        if(valType == TYPE_INVALID) {
            throw new InvalidParameterException("invalid type " + segType);
        }

        String s = rs.substring(pos + 1).trim();
        int relation = getRelation(s);
        if(relation == NOTMATCH || relation == MATCH) {
            if(valType != TYPE_STRING) {
                throw new InvalidParameterException("~,!~ can only be used with string");
            }
        } else if((relation & BIGGER) == BIGGER
            || (relation & SMALLER) == SMALLER) {
            if(valType != TYPE_STRING && !isNumber(valType)) {
                throw new InvalidParameterException(">,<,>= and <= can only be used with string and number");
            }
        } else if(relation == IN || relation == NOTIN) {
            if(valType != TYPE_STRING && !isNumber(valType)) {
                throw new InvalidParameterException("@ and !@ can only be used with string and number");
            }
        }
        
        return (valType << 16) | relation;
    }

    private static int getRelation(String s) throws InvalidParameterException {
        if(s.equals("==")) {
            return EQUAL;
        }
        if(s.equals("!=")) {
            return NOTEQUAL;
        }
        if(s.equals(">=")) {
            return BIGGER + EQUAL;
        }
        if(s.equals(">")) {
            return BIGGER;
        }
        if(s.equals("<=")) {
            return SMALLER + EQUAL;
        }
        if(s.equals("<")) {
            return SMALLER;
        }
        if(s.equals("~")) {
            return MATCH;
        }
        if(s.equals("!~")) {
            return NOTMATCH;
        }
        if(s.equals("@")) {
            return IN;
        }
        if(s.equals("!@")) {
            return NOTIN;
        }
        throw new InvalidParameterException("invalid condition " + s);
    }
    
    public static <T extends Comparable<T>> boolean compare(T a, T b, int relation) {
        int v = a.compareTo(b);
        if(v == 0) {
            if(relation == NOTEQUAL) {
                return false;
            }
            return (relation & EQUAL) == EQUAL;
        } else if(relation == NOTEQUAL) {
            return true;
        }

        if(v > 0) {
            return (relation & MASK) == BIGGER;
        }
        return (relation & MASK) == SMALLER;
    }
    
    public static <T extends Comparable<T>> boolean biggerThan(T a, T b) {
        return compare(a, b, BIGGER);
    }
    
    public static <T extends Comparable<T>> boolean smallerThan(T a, T b) {
        return compare(a, b, SMALLER);
    }
    
    public static int parseType(String s) {
        String t = s.trim().toLowerCase();
        if(t.equals("i") || t.equals("int")) {
            return TYPE_INT;
        }

        if(t.equals("l") || t.equals("long")) {
            return TYPE_LONG;
        }

        if(t.equals("s") || t.equals("string")) {
            return TYPE_STRING;
        }
        
        if(t.equals("d") || t.equals("double")) {
            return TYPE_DOUBLE;
        }
        
        if(t.equals("f") || t.equals("float")) {
            return TYPE_FLOAT;
        }
        
        if(t.equals("o") || t.equals("object")) {
            return TYPE_OBJECT;
        }
        
        if(t.equals("b") || t.equals("bool") || t.equals("boolean")) {
            return TYPE_BOOL;
        }
        
        if(t.equals("c") || t.equals("char")) {
            return TYPE_CHAR;
        }
        
        return TYPE_INVALID;
    }
    
    public static boolean isNumber(int valType) {
        switch(valType) {
        case TYPE_INT:
        case TYPE_LONG:
        case TYPE_DOUBLE:
        case TYPE_FLOAT:
            return true;
        default: return false;
        }
    }
}