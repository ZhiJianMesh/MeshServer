package cn.net.zhijian.mesh.frm.service.backend;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;

/**
 * 列举存在的日志文件
 * @author flyinmind of csdn.net
 *
 */
final class ListLogs extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();

    public ListLogs(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String logPath = LogUtil.getLogPath();
        LOG.debug("logs path:{}", logPath);
        File logsDir = new File(logPath);
        File[] files;

        if(!logsDir.exists() || (files = logsDir.listFiles()) == null) {
            LOG.error("Can't find logs dir {}", logsDir);
            return futureResult(HandleResult.InternalError);
        }

        int rootLen = LogUtil.getLogPath().length();
        List<String> logFiles = new ArrayList<>();
        for(File sub : files) {
            try {
                if(sub.isDirectory()) {
                    File[] ff = sub.listFiles();
                    if(ff != null) {
                        for (File f : ff) {
                            String s = f.getCanonicalPath().substring(rootLen);
                            String ext = FileUtil.getFileExtension(s);
                            if("gz".equals(ext) || "log".equals(ext)) {
                                logFiles.add(s.replace("\\", "/"));
                            }
                        }
                    }
                } else {
                    String logFile = sub.getCanonicalPath().substring(rootLen);
                    String ext = FileUtil.getFileExtension(logFile);
                    if("gz".equals(ext) || "log".equals(ext)) {
                        logFiles.add(logFile.replace("\\", "/"));
                    }
                }
            } catch (IOException e) {
                LOG.error("Fail to getCanonicalPath", e);
            }
        }
        return futureResult(MapBuilder.of("list", logFiles));
    }
}