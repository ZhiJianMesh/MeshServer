package cn.net.zhijian.mesh.frm.httpcb;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.NodeAddress;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsHttpCallback;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;
import okhttp3.Call;
import okhttp3.Response;

public class BigFileDownloadCallback extends AbsHttpCallback<HandleResult> {
    private static final Logger LOG = LogUtil.getInstance();

    private final String saveAs;
    private int size = 0;
    private final IDownloadProgress progress;

    @Override
    public int onSuccess(Response resp) {
        if (!resp.isSuccessful()) {
            LOG.error("Fail to download {},{}, status code:{}",
                    resp.request().url(), traceId, resp.code());
            super.complete(new HandleResult(RetCode.UNKNOWN_ERROR, "Fail to download"));
            return RetCode.UNKNOWN_ERROR;
        }

        LOG.info("Save `{}` to `{}`", resp.request().url(), saveAs);
        File saveAsFile = new File(saveAs);
        File path = saveAsFile.getParentFile();
        if(path == null) {
            super.complete(new HandleResult(RetCode.INTERNAL_ERROR, "Fail to create directory"));
            return RetCode.INTERNAL_ERROR;
        }

        if(!path.exists()) {
            if(!path.mkdirs()) {
                LOG.error("Fail to create dir {}", path); //失败后，仍然继续尝试
            }
        }

        HandleResult hr;
        try(InputStream inputStream = Objects.requireNonNull(resp.body()).byteStream();
            FileOutputStream fileOutputStream = new FileOutputStream(saveAsFile)) {
            byte[] buffer = new byte[2048];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                size += len;
                progress.progress(size);
                fileOutputStream.write(buffer, 0, len);
            }
            fileOutputStream.flush();
            Map<String, Object> data = MapBuilder.of("saveAs", saveAs, "size", size);
            hr = new HandleResult(RetCode.OK, data);
        } catch (IOException e) {
            LOG.error("Fail to save `{}` to {},{}",
                    resp.request().url(), saveAs, traceId, e);
            hr = new HandleResult(RetCode.INTERNAL_ERROR, "Fail to get the file");
        }
        super.complete(hr);
        return hr.code;
    }

    @Override
    public void onException(Call call, Throwable e) {
        if(LOG.isDebugEnabled()) { //在AbsHttpCallback已打印异常，此处不必再次打印
            LOG.error("Fail to download {}@{},{}", call.request().url(), traceId, e.getMessage());
        }
        super.complete(new HandleResult(RetCode.INTERNAL_ERROR, "Fail to download"));
    }

    /**
     * @param node     被请求实例
     * @param traceId 跟踪ID
     * @param saveAs  将下载的内容另存到的文件
     */
    public BigFileDownloadCallback(NodeAddress node, String traceId, String saveAs, IDownloadProgress progress) {
        super(node, traceId);
        this.saveAs = saveAs;
        this.progress = progress;
    }
}

