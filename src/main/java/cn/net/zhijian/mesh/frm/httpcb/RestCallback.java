package cn.net.zhijian.mesh.frm.httpcb;

import java.io.IOException;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsHttpCallback;
import cn.net.zhijian.util.LogUtil;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class RestCallback extends AbsHttpCallback<HandleResult> {
    private static final Logger LOG = LogUtil.getInstance();

    public RestCallback(NodeAddress node, String traceId) {
        super(node, traceId);
    }

    @Override
    public int onSuccess(Response resp) {
        HandleResult hr;
        String url = resp.request().url().toString();
        if (!resp.isSuccessful()) {
            LOG.error("RestCall response is not successful, status code:{}", resp.code());
            hr = new HandleResult(RetCode.EMPTY_BODY, "response body is empty");
        } else {
            try (ResponseBody body = resp.body()) {
                if (body != null) {
                    hr = HandleResult.fromStr(body.string());
                } else {
                    hr = new HandleResult(RetCode.EMPTY_BODY, "response body is empty");
                }
            } catch (IOException e) {
                LOG.error("Fail to get response from {}", url, e);
                hr = new HandleResult(RetCode.INTERNAL_ERROR, "fail to read response body");
            }
        }

        if (LOG.isDebugEnabled()) {
            //如果放在super.complete(hr)之后，经常发生ConcurrentModificationException异常
            //因为调用hr.toString时，其他线程可能正在写入map
            LOG.debug("onSuccess({}),{},result:{}", resp.request().url(), traceId, hr.toString(20));
        }
        super.complete(hr);
        
        return hr.code;
    }

    @Override
    public void onException(Call call, Throwable e) {
        //在AbsHttpCallback已打印过此异常，这里只作简单的输出
        if(LOG.isDebugEnabled()) {
            LOG.error("Fail to call {}@{},{}", call.request().url(), traceId, e.getMessage());
        }
        super.complete(new HandleResult(RetCode.INTERNAL_ERROR, "internal exception"));
    }
}

