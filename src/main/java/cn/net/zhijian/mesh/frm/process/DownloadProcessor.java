package cn.net.zhijian.mesh.frm.process;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.BigFileReader;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 文件下载处理器
 * 使用json体分片下载文件，实现类似断点续传的能力。
 * 可以在多个实例上运行，每个实例上的文件需要一致
 * 文件加载后不会关闭，只有在系统停止时才会关闭
 * @author flyinmind@zhijian.net.cn
 */
public class DownloadProcessor extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    private static final int PKG_SIZE = 15 * 1024;
    
    private static final String CFG_PATH = "path"; //上传后，文件存放路径
    
    private static final String R_START = "start";
    private static final String REQ_FILENAME = "name";
    private static final String R_FILENO = "fileNo";
    
    private static final String RESP_CONTENT = "content";
    private static final String RESP_FILESIZE = "size";
    private static final String RESP_DIGEST = "digest";
    private static final String RESP_PKGSIZE = "pkgSize";

    private ScriptElement[] path; //存放文件的目录，只能存在服务自己的根目录下
    
    public DownloadProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        Map<String, Object> params = req.params();
        String fileNo = ValParser.getAsStr(params, R_FILENO);
        if(!StringUtil.isEmpty(fileNo)) { //没有文件号，表示是第一个请求，有文件号表示开始下载
            BigFileReader fr = BigFileReader.get(fileNo);
            if(fr == null) {
                return HandleResult.future(RetCode.NOT_EXISTS, "not exists");
            }
            int start = ValParser.getAsInt(params, R_START);
            try {
                byte[] content = fr.read(start, PKG_SIZE);
                Map<String, Object> data = MapBuilder.of(RESP_CONTENT, ByteUtil.bin2base64(content), R_START, start);
                if(fr.size == content.length + start) {
                    data.put(HandleResult.CODE, RetCode.OK);
                    data.put(HandleResult.INFO, "OVER");
                } else {
                    data.put(HandleResult.CODE, RetCode.OK);
                    data.put(HandleResult.INFO, RetCode.INFO_SUCCESS);
                }
                return HandleResult.future(data);
            } catch (IOException e) {
                LOG.error("Fail to write file", e);
                return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to write file");
            }
        }

        String name = URLDecoder.decode(ValParser.getAsStr(params, REQ_FILENAME), IConst.DEFAULT_CHARSET);
        if(name.contains("./")) {//不容许相对路径
            return HandleResult.future(RetCode.WRONG_PARAMETER, "invalid filename,contains relative path");
        }
        String fullName = fullPath(req, resp, name);
        if(!new File(fullName).exists()) {
            return HandleResult.future(RetCode.NOT_EXISTS, "not exists");
        }
        
        fileNo = SecureUtil.md5(fullName);
        try {
            BigFileReader fr = BigFileReader.get(fileNo); //不可以在此关闭，所以不能用try-with
            if(fr == null) {
                fr = BigFileReader.add(fileNo, fullName); //第一个请求，不携带文件内容
            }
            Map<String, Object> data = MapBuilder.of(R_FILENO, fileNo, RESP_PKGSIZE, PKG_SIZE);
            data.put(RESP_FILESIZE, fr.size);
            data.put(RESP_DIGEST, fr.digest);
            return HandleResult.future();
        } catch (IOException e) {
            LOG.error("Fail to create file record,{}", fullName, e);
            return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to create file");
        }
    }

    protected String fullPath(AbsServerRequest req, Map<String, Object> resp, String name) {
        String path = translateElements(this.path, req, resp);
        return FileUtil.addPath(path, name);
    }
    
    @Override
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        String path = ValParser.getAsStr(cfg, CFG_PATH);
        if(StringUtil.isEmpty(path)) {
            LOG.error("Invalid config item {} in `{}`", CFG_PATH, url.toString());
            return false;
        }
        path = FileUtil.addPath(serviceInfo().homeDir, path);
        LOG.debug("Config `{}` is `{}`", CFG_PATH, path);
        Set<String> allParas = availableParas(request);
        this.path = ScriptElement.parsePlaceHolder(path, allParas, IConst.EMPTY_STR, null);
        if(this.path == null || this.path.length == 0) {
            LOG.error("Fail to parse {} under {}", CFG_PATH, url.toString());
            return false;
        }
        
        return super.parse(url, cfg, request);
    }
}
