package cn.net.zhijian.mesh.bean;

public class TV {
    public static final int TYPE_INVALID = 0;
    public static final int TYPE_INT = 1;
    public static final int TYPE_LONG = 2;
    public static final int TYPE_FLOAT = 3;
    public static final int TYPE_DOUBLE = 4;
    public static final int TYPE_STRING = 5;
    public static final int TYPE_OBJECT = 6;
    public static final int TYPE_BOOL = 7;
    public static final int TYPE_CHAR = 8;
    public static final int TYPE_SIZE = 9;
    
    
    public final int type;
    public final Object value;

    public TV(Object value) {
        this.type = TYPE_OBJECT;
        this.value = value;
    }
    
    private TV(int type, Object value) {
        this.type = type;
        this.value = value;
    }
    
    public TV(int value) {
        this.type = TYPE_INT;
        this.value = value;
    }
    
    public TV(long value) {
        this.type = TYPE_LONG;
        this.value = value;
    }
    
    public TV(float value) {
        this.type = TYPE_FLOAT;
        this.value = value;
    }
    
    public TV(double value) {
        this.type = TYPE_DOUBLE;
        this.value = value;
    }
    
    public TV(String value) {
        this.type = TYPE_STRING;
        this.value = value;
    }
    
    public TV(char value) {
        this.type = TYPE_CHAR;
        this.value = value;
    }
    
    public static TV size(int value) {
        return new TV(TYPE_SIZE, value);
    }
    
    public static TV invalid() {
        return new TV(TYPE_SIZE, null);
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
        
        if(t.equals("size")) {
            return TYPE_SIZE;
        }
        
        return TYPE_INVALID;
    }
    
    public static boolean isNumber(int valType) {
        switch(valType) {
        case TYPE_INT:
        case TYPE_LONG:
        case TYPE_DOUBLE:
        case TYPE_FLOAT:
        case TYPE_SIZE:
            return true;
        default: return false;
        }
    }
}