package cn.net.zhijian.mesh.bean;

import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.UrlPathInfo;

/**
 * webdb服务节点信息，包括健康状况
 * check函数提供了检测功能
 * @author flyinmind of csdn.net
 *
 */
public class DBNodeAddress extends NodeAddress {
    private static final Logger LOG = LogUtil.getInstance();
    private final String checkupUrl;
    private final String db;
    
    /**
     * @param service 服务名
     * @param db 服务的数据库名
     * @param addr ip地址+端口号
     * @param ver 服务版本号Semantic
     * @param level 等级
     */
    public DBNodeAddress(String service, String db, String addr, int ver, int level) {
        super(service, addr, ver, level);
        this.db = db;
        this.checkupUrl = new UrlPathInfo(IConst.API_CHECKUP)
                .appendPara(IDBConst.DB_REQ_SERVICE, service, false)
                .appendPara(IDBConst.DB_REQ_DB, db, false).toString();
    }
    
    public DBNodeAddress(String service, String db, String addr) {
        this(service, db, addr, 1, 0);
    }

    @Override
    public void check(int cid) {
        int cur = curTime();
        if(cur < nextCheckTime) {
            return;
        }
        Map<String, String> headers = MapBuilder.of(IConst.HEAD_CID, Integer.toString(cid));
        nextCheckTime = cur + CHECK_INTERVAL; //阻挡并发的多余check
        //调用webdb的checkup接口，与其他服务的checkup实现不同，
        //此接口需要传递服务名、数据库名
        //首先webdb服务需要正常，其次对应的db需要正常
        ServiceClient.serviceGet(addr, IConst.SERVICE_WEBDB, checkupUrl, headers, "").whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.info("Check {}.{}@{},failed", service, db, addr, e);
                return;
            }
            
            if(hr.code == RetCode.OK) {
                reset();
                LOG.info("Check {}.{}@{},succeed", service, db, addr);
            } else {
                LOG.info("Check {}.{}@{},code:{},faild", service, db, addr, hr.code);
            }
        }, IThreadPool.Pool);
    }
}
