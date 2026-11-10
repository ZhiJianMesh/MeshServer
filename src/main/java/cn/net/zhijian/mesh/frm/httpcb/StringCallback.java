package cn.net.zhijian.mesh.frm.httpcb;

import java.io.IOException;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsHttpCallback;
import cn.net.zhijian.util.HttpResp;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;

public class StringCallback extends AbsHttpCallback<String> {
    private static final Logger LOG = LogUtil.getInstance();

    public StringCallback(NodeAddress node, String traceId) {
        super(node, traceId);
    }

    @Override
    public int onSuccess(HttpResp resp) {
        String txt = null;
        String url = resp.url();
        int resultCode = RetCode.OK;
        if (!resp.isSuccessful()) {
            LOG.error("RestCall response is not successful, status code:{}", resp.code());
            resultCode = RetCode.INTERNAL_ERROR;
        } else {
            try {
                txt = resp.string(); //HTTP错误描述
            } catch (IOException e) {
                LOG.error("Fail to get response from {}", url, e);
                resultCode = RetCode.INTERNAL_ERROR;
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("onResponse {},result:{}", resp.url(), StringUtil.shorten(txt, 80));
        }
        super.complete(txt);
        return resultCode;
    }

    @Override
    public void onException(String url, Throwable e) {
        if (LOG.isDebugEnabled()) { //在AbsHttpCallback已打印异常，此处不必再次打印
            LOG.error("Fail to call {}@{},{}", url, traceId, e.getMessage());
        }
        super.complete(null);
    }
}
