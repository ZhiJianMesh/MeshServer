package cn.net.zhijian.mesh.frm.httpcb;

import java.io.IOException;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsHttpCallback;
import cn.net.zhijian.util.LogUtil;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class BinaryCallback extends AbsHttpCallback<byte[]> {
    private static final Logger LOG = LogUtil.getInstance();

    public BinaryCallback(NodeAddress node, String traceId) {
        super(node, traceId);
    }
    
    @Override
    public int onSuccess(Response resp) {
        if (!resp.isSuccessful()) {
            LOG.warn("Download response is not successful, status code:{}", resp.code());
            super.complete(null);
            return RetCode.NOT_EXISTS;
        }
        
        try (ResponseBody body = resp.body()) {
            if(body == null) {
                super.complete(null);
                return RetCode.EMPTY_BODY; 
            }
            super.complete(body.bytes());
            return RetCode.OK;
        } catch (IOException e) {
            LOG.error("Fail to get `{}`", resp.request().url(), e);
            super.complete(null);
            return RetCode.INTERNAL_ERROR;
        }
   }

    @Override
    public void onException(Call call, Throwable e) {
        if(LOG.isDebugEnabled()) { //在AbsHttpCallback已打印异常，此处不必再次打印
            LOG.error("Fail to get binary {}@{},{}", call.request().url(), traceId, e.getMessage());
        }
        super.complete(null);
    }
}

