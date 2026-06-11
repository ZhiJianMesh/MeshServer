package cn.net.zhijian.mesh.frm.httpcb;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.bean.ProxyResponse;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsHttpCallback;
import cn.net.zhijian.util.LogUtil;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ProxyBinaryCallback extends AbsHttpCallback<ProxyResponse> {
    private static final Logger LOG = LogUtil.getInstance();

    public ProxyBinaryCallback(NodeAddress node, String traceId) {
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
            Map<String, String> headers = new HashMap<>();
            Map<String, List<String>> hh = resp.headers().toMultimap();
            for(Map.Entry<String, List<String>> h : hh.entrySet()) {
                List<String> l = h.getValue();
                if(l != null && !l.isEmpty()) {
                    headers.put(h.getKey(), l.get(0));
                }
            }
            super.complete(new ProxyResponse(headers, body.bytes()));
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

