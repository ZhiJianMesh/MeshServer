package cn.net.zhijian.mesh.frm.abs;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.util.HttpCallback;
import cn.net.zhijian.util.HttpResp;
import cn.net.zhijian.util.LogUtil;

public abstract class AbsHttpCallback<T> extends CompletableFuture<T> implements HttpCallback {
    private static final Logger ACCESS = LogUtil.getInstance("ACCESS");
    private static final Logger LOG = LogUtil.getInstance();

    public final String traceId;
    public final NodeAddress node;
    public final long start = System.currentTimeMillis();

    public abstract int onSuccess(HttpResp resp);

    public abstract void onException(String url, Throwable e);

    /**
     * @param node    被调节点
     * @param traceId 跟踪ID
     */
    public AbsHttpCallback(NodeAddress node, String traceId) {
        this.traceId = traceId;
        this.node = node;
    }

    @Override
    public void onFailure(Throwable e, String url, String method) {
        // method,useTime,statusCode,resultCode,uri,traceId
        ACCESS.info("C\t{}\t\t{}\t-1\t-1\t{}\t{}",
                method, System.currentTimeMillis() - start,
                url, traceId);
        if (LOG.isDebugEnabled()) { //debug打开时，会打印异常调用栈
            LOG.error("Fail to asyncSend({}\t{})", url, traceId, e);
        } else {
            LOG.error("Fail to asyncSend({}\t{}),{}", url, traceId, e.getMessage());
        }

        // 只有发生异常时，才会报告节点异常，业务层的错误在业务层处理，不认为节点异常
        if (node != null) {
            node.report(false);
        }
        onException(url, e);
    }

    @Override
    public void onResponse(HttpResp resp) {
        if (node != null) {
            node.reset();
        }
        int resultCode = onSuccess(resp);
        // method,useTime,statusCode,resultCode,uri,traceId
        ACCESS.info("C\t{}\t{}\t{}\t{}\t{}\t{}",
                resp.method(), System.currentTimeMillis() - start,
                resp.code(), resultCode, resp.url(), traceId);
    }

    public interface IDownloadProgress {
        void progress(int size);
        void message(String msg);
    }
}
