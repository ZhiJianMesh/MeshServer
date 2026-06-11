package cn.net.zhijian.mesh.frm.httpcb;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsHttpCallback;
import cn.net.zhijian.util.HttpUtil;
import cn.net.zhijian.util.LogUtil;
import okhttp3.Call;
import okhttp3.Response;

public class DownloadCallback extends AbsHttpCallback<HandleResult> {
    private static final Logger LOG = LogUtil.getInstance();

    private final String saveAs;

    /**
     * 
     * @param node    被调节点
     * @param traceId 跟踪ID
     * @param saveAs  保存为的文件
     */
    public DownloadCallback(NodeAddress node, String traceId, String saveAs) {
        super(node, traceId);
        this.saveAs = saveAs;
    }

    @Override
    public int onSuccess(Response resp) {
        if (!resp.isSuccessful()) {
            LOG.warn("Download response is not successful, status code:{}", resp.code());
            super.complete(new HandleResult(RetCode.INTERNAL_ERROR, "invalid response"));
            return RetCode.INTERNAL_ERROR;
        }

        String url = resp.request().url().toString();
        LOG.debug("Save `{}` to `{}`,code:{}", url, saveAs, resp.code());
        File saveAsFile = new File(saveAs);
        File path = saveAsFile.getParentFile();
        if(path == null) {
            LOG.error("Path `{}` not exists", saveAs);
            super.complete(new HandleResult(RetCode.INTERNAL_ERROR, "invalid path"));
            return RetCode.INTERNAL_ERROR;
        }
        if(!path.exists()) {
            if (!path.mkdirs()) { //创建失败，仍然继续
                LOG.error("Fail to create dir {}, maybe there are concurrent threads", path);
            }
        }

        HandleResult hr;
        try{
            HttpUtil.saveRespToFile(saveAsFile, resp);
            hr = HandleResult.OK;
        } catch (IOException e) {
            LOG.error("Fail to save `{}` to {}", url, saveAs, e);
            hr = new HandleResult(RetCode.INTERNAL_ERROR, "fail to save file");
        }
        super.complete(hr);
        return hr.code;
    }

    @Override
    public void onException(Call call, Throwable e) {
        if(LOG.isDebugEnabled()) { //在AbsHttpCallback已打印异常，此处不必再次打印
            LOG.error("Fail to download {}@{},{}", call.request().url(), traceId, e.getMessage());
        }
        super.complete(new HandleResult(RetCode.INTERNAL_ERROR, "http request failed"));
    }
}


