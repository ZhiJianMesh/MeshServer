package cn.net.zhijian.mesh.bean;

/**
 * 值，用在解析结果中，类型不确定，可以是任意类型值，
 * 如果解析错误，则ok为false，并且errInfo可能不为空
 * @author flyinmind of csdn.net
 *
 */
public final class Value {
    public final boolean changed;
    public final Object v;
    public final boolean ok;
    public final String errInfo;
    
    private Value(boolean ok, boolean changed, Object v, String errInfo) {
        this.changed = changed;
        this.v = v;
        this.ok = ok;
        this.errInfo = errInfo;
    }
    
    public static Value failed(String errInfo) {
        return new Value(false, false, null, errInfo);
    }

    public static Value none() {
        return new Value(true, false, null, null);
    }

    public static Value success(boolean changed, Object v) {
        return new Value(true, changed, v, null);
    }
}
