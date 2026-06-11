package cn.net.zhijian.mesh.frm.abs;

import cn.net.zhijian.mesh.bean.DeferrableEle;

/**
 * DNS抽象类，用于ServiceDNS等
 * @author flyinmind of csdn.net
 *
 */
public abstract class AbstractDNS extends DeferrableEle {
    public AbstractDNS(int expiresIn) {
        super(expiresIn);
    }

    public abstract String name();
}