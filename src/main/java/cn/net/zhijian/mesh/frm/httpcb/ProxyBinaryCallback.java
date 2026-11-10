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
import cn.net.zhijian.util.HttpResp;
import cn.net.zhijian.util.LogUtil;

public class ProxyBinaryCallback extends AbsHttpCallback<ProxyResponse> {
    private static final Logger LOG = LogUtil.getInstance();

    public ProxyBinaryCallback(NodeAddress node, String traceId) {
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
            Map<String, String> headers = new HashMap<>();
            Map<String, List<String>> hh = resp.headers();
            for(Map.Entry<String, List<String>> h : hh.entrySet()) {
                List<String> l = h.getValue();
                if(l != null && !l.isEmpty()) {
                    headers.put(h.getKey(), l.get(0));
                }
            }
            super.complete(new ProxyResponse(headers, resp.bytes()));
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
