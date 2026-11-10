package cn.net.zhijian.mesh.frm.httpcb;

import java.io.IOException;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsHttpCallback;
import cn.net.zhijian.util.HttpResp;
import cn.net.zhijian.util.LogUtil;

public class BinaryCallback extends AbsHttpCallback<byte[]> {
    private static final Logger LOG = LogUtil.getInstance();

    public BinaryCallback(NodeAddress node, String traceId) {
        super(node, traceId);
    }
    
    @Override
    public int onSuccess(HttpResp resp) {
        if (!resp.isSuccessful()) {
            LOG.warn("Download response is not successful, status code:{}", resp.code());
            super.complete(null);
            return RetCode.NOT_EXISTS;
        }
        
        try {
            if(resp.body() == null) {
                super.complete(null);
                return RetCode.EMPTY_BODY; 
            }
            super.complete(resp.bytes());
            return RetCode.OK;
        } catch (IOException e) {
            LOG.error("Fail to get `{}`", resp.url(), e);
            super.complete(null);
            return RetCode.INTERNAL_ERROR;
        }
   }

    @Override
    public void onException(String url, Throwable e) {
        if(LOG.isDebugEnabled()) { //在AbsHttpCallback已打印异常，此处不必再次打印
            LOG.error("Fail to get binary {}@{},{}", url, traceId, e.getMessage());
        }
        super.complete(null);
    }
}
