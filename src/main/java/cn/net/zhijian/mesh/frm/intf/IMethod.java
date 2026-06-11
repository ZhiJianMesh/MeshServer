package cn.net.zhijian.mesh.frm.intf;

/**
 * REST接口实现的接口类，所有REST接口必须实现此接口
 * @author flyinmind of csdn.net
 *
 */
public interface IMethod {
    /**
     * 是否为私有method，如果是私有的，必须验证token后才可以调用
     * @return 是否为私有API
     */
    boolean isPrivate();
    boolean isValidMethod(String method);
    void destroy();
}