package cn.net.zhijian.mesh;

/**
 * mesh异常
 * @author flyinmind of csdn.net
 *
 */
public final class MeshException extends Exception {
    private static final long serialVersionUID = 0x9877456788L;

    public MeshException(String message) {
        super(message);
    }

    public MeshException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public MeshException(Throwable cause) {
        super(cause);
    }
}
