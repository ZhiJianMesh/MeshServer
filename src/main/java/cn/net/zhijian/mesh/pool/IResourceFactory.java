package cn.net.zhijian.mesh.pool;

import cn.net.zhijian.mesh.MeshException;

/**
 * 资源工厂接口
 * @author flyinmind of csdn.net
 *
 */
public interface IResourceFactory<T extends AbsResource> {
    T create() throws MeshException;
}
