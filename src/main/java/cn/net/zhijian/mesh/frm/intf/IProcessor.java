package cn.net.zhijian.mesh.frm.intf;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.Transcoder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;

/**
 * 业务处理接口
 * @author flyinmind of csdn.net
 * services
 *  |_serviceName
 *     |__api(api configs)
 *     |__file(static files, for example, templates)
 *     |__logs(created when running)
 *     |__database.cfg(databse/tables define)
 *     |__service.cfg
 */
public interface IProcessor extends IConst, IThreadPool {
    String TYPE_JS = "js";
    String TYPE_JAVA = "java";
    String TYPE_RDB = "rdb";
    String TYPE_UPLOAD = "upload";
    String TYPE_DOWNLOAD = "download";
    String TYPE_TREEDB = "treedb"; //基于rdb，固定的只有dir、item两个表
    String TYPE_SEARCH = "search"; //全文索引db
    String TYPE_LOC_RDB = "localrdb"; //本地关系型数据库，不支持分库，不支持同步
    String TYPE_LOC_TREEDB = "localtreedb"; //本地treedb
    String TYPE_LOC_SEARCH = "localsearch"; //本地全文索引db
    String TYPE_CALL = "call"; //call a service
    String TYPE_PROXY = "proxy"; //以服务自身的权限请求其他服务
    String TYPE_VAR = "var"; //定义变量，这些变量可以作为后继处理的参数
    String TYPE_DATAEXISTS = "dataexists"; //判断数据是否存在
    String TYPE_LOGIC = "logic"; //一组用@{CONDITION}实现的逻辑表达式
    String TYPE_STATIC = "static"; //返回静态内容
    String TYPE_TCPX = "tcpx"; //转发给TCP长连接客户端的请求处理器

    Transcoder trans(int code);
    String name();
    ServiceInfo serviceInfo();
    default void destroy() {}

    /**
     * 
     * @param req 从请求方传递来的参数
     * @param resp  前面的handle返回的结果全部集中到resp中。
     *     在ProcessInfo.handle中，每执行完成一个handle，如果code为OK，都会将data全部存入resp。
     *     handler.handle中，可以将结果存入resp，返回的结果中data可以为空，这样做不完美，但是方便省时。
     *  在后面的处理的配置中可以通过@{!xxx}引用resp中的信息。
     *  如果中间步骤的结果不需要返回，可以通过配置response，
     *  通过它过滤后只会剩下在response中定义过的字段
     * @return 异步处理结果
     */
    CompletableFuture<HandleResult> handleAll(AbsServerRequest req, Map<String, Object> resp);
}