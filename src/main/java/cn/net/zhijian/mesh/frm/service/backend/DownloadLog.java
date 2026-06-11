package cn.net.zhijian.mesh.frm.service.backend;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.tokenchecker.TokenCheckers;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.method.DirFileMethod;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * 下载日志文件，路径指向logs存放路径，
 * 参数n指定要下载的日志
 * 
 * @author flyinmind of csdn.net
 *
 */
final class DownloadLog extends DirFileMethod {
    private static final Logger LOG = LogUtil.getInstance();

    public DownloadLog(ServiceInfo si, String homeDir) {
        super(si, homeDir);
    }

    @Override
    public void response(AbsServerRequest req, AbsServerResponse resp) {
        String token = req.header(IConst.HEAD_ACCESS_TOKEN);
        if(StringUtil.isEmpty(token)) {
            resp.error(HttpResponseStatus.UNAUTHORIZED, RetCode.NO_RIGHT);
            return;
        }
        AccessToken at = null;
        try {
            at = TokenCheckers.Backend.check(req, token).get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.error("Fail to check token", e);
        }
        if(at == null || at.expired()) {
            resp.error(HttpResponseStatus.UNAUTHORIZED, RetCode.NO_RIGHT);
            return;
        }

        super.response(req, resp);
    }
}