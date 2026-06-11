package cn.net.zhijian.mesh.frm.abs;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.util.LogUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public abstract class AbsHttpCallback<T> extends CompletableFuture<T> implements Callback {
    private static final Logger ACCESS = LogUtil.getInstance("ACCESS");
    private static final Logger LOG = LogUtil.getInstance();

    public final String traceId;
    public final NodeAddress node;
    public final long start = System.currentTimeMillis();

    public abstract int onSuccess(Response resp);

    public abstract void onException(Call call, Throwable e);

    /**
     * @param node    被调节点
     * @param traceId 跟踪ID
     */
    public AbsHttpCallback(NodeAddress node, String traceId) {
        this.traceId = traceId;
        this.node = node;
    }

    @Override
    public void onFailure(Call call, IOException e) {
        // method,useTime,statusCode,resultCode,uri,traceId
        String url = call.request().url().toString();
        ACCESS.info("C\t{}\t\t{}\t-1\t-1\t{}\t{}",
                call.request().method(), System.currentTimeMillis() - start,
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
        onException(call, e);
    }

    @Override
    public void onResponse(Call call, Response resp) {
        if (node != null) {
            node.reset();
        }
        int resultCode = onSuccess(resp);
        String url = resp.request().url().toString();
        // method,useTime,statusCode,resultCode,uri,traceId
        ACCESS.info("C\t{}\t{}\t{}\t{}\t{}\t{}",
                resp.request().method(), System.currentTimeMillis() - start,
                resp.code(), resultCode, url, traceId);
    }

    public interface IDownloadProgress {
        void progress(int size);
        void message(String msg);
    }
}
