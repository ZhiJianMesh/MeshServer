package cn.net.zhijian.mesh.frm.config;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.BigFileReader;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.bean.Value;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsFileMethod;
import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.para.ParameterInfo;
import cn.net.zhijian.mesh.frm.config.placeholder.ScriptElement;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.mesh.js.JsEngine;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.HttpUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * 接口响应相关的配置
 * @author flyinmind of csdn.net
 *
 */
public final class ResponseInfo {
    private static final Logger LOG = LogUtil.getInstance();

    private static final String PROPERTY_RESPONSE = "response";
    private static final String PROPERTY_SEGMENTS = "segments";
    private static final String PROPERTY_CHECK = "check";
    private static final String PROPERTY_TEMPLATE = "template";
    private static final String PROPERTY_ISZIP = "isZip";
    //zip解开后的临时目录
    private static final String PROPERTY_ROOTDIR = "root_dir";
    private static final String PROPERTY_SAVEAS = "saveAs";

    private static final String PROPERTY_TYPE = "type"; //JSON/DOCX
    
    private enum ResponseType {JSON, DOCX, XLSX, TEXT}

    //响应字段，如果为空表示不处理，原样返回，长度为0表示删除所有响应
    private final ParameterInfo[] segments;
    //是否检查，false表示原样返回，segments只用于阅读
    private final boolean check;
    private final IResponseHandler handler;

    public ResponseInfo(ParameterInfo[] segments) {
        this(segments, false, DEFAUTL_HANDLER);
    }
    
    private ResponseInfo(ParameterInfo[] segments, boolean check, IResponseHandler handler) {
        this.segments = segments;
        boolean chk = check;
        if(segments != null && !check) {
            for(ParameterInfo pi : segments) { //只要有一个json字段，则check必须为true
                if(pi.type().equals(ParameterInfo.TYPE_JSON)) {
                    chk = true;
                    break;
                }
            }
        }
        this.check = chk;
        this.handler = handler;
    }

    public static ResponseInfo parse(ServiceInfo si, Set<String> params, Map<String, Object> cfg) {
        Object respCfg = cfg.get(PROPERTY_RESPONSE);
        if(respCfg == null) {//no response config
            return new ResponseInfo(null, false, DEFAUTL_HANDLER);
        }

        boolean check = true;
        List<Object> segs;
        //只有JSON方式可以单例，因为它没有特别的配置信息
        IResponseHandler handler = DEFAUTL_HANDLER;

        if(respCfg instanceof List<?>) {
            segs = ValParser.parseList(respCfg);
        } else if(respCfg instanceof Map<?, ?>) {
            Map<String, Object> respMap = ValParser.parseObject(respCfg);
            segs = ValParser.getAsList(respMap, PROPERTY_SEGMENTS);
            check = ValParser.getAsBool(respMap, PROPERTY_CHECK, true);
            String type = ValParser.getAsStr(respMap, PROPERTY_TYPE, ResponseType.JSON.name()).toUpperCase();
            if(type.equals(ResponseType.DOCX.name())) {
                handler = new DocxResponseHandler();
            } else if(type.equals(ResponseType.XLSX.name())) { 
                handler = new XlsxResponseHandler();
            } else if(type.equals(ResponseType.TEXT.name())) { 
                handler = new TextResponseHandler();
            } else if(!type.equals(ResponseType.JSON.name())) {
                LOG.error("Response type {} not supported", type);
                return null; 
            }
            
            if(!handler.parse(si, params, respMap)) {
                LOG.error("Fail to parse {}", respMap);
                return null;
            }
        } else {
            LOG.error("Invalid response config {}", respCfg);
            return null; //must be list or map
        }

        if(segs == null) {
            return new ResponseInfo(null, false, handler); //no response config
        }

        /*
         * 无response与response无字段，含义不同：
         * 无resonose配置，表示程序中有什么输出，原封不动的返回，不做任何判断；
         * 有response，但是无字段，表示没有data返回，即使有，也要清除
         */
        if(segs.isEmpty()) {
            return new ResponseInfo(new ParameterInfo[0], check, handler);
        }        

        ParameterInfo[] segments = new ParameterInfo[segs.size()];
        int idx = 0;
        for(Object seg : segs) {
            Map<String, Object> one = ValParser.parseObject(seg);
            if(one == null || one.isEmpty()) {
                LOG.error("Invalid response segments definition at {}", idx);
                return null;
            }

            ParameterInfo pi = ParameterInfo.parse(one, true);
            if (pi == null) {
                LOG.error("Invalid response parameter definition at {}", idx);
                return null;
            }
            segments[idx] = pi;
            idx++;
        }

        return new ResponseInfo(segments, check, handler);
    }

    public void response(AbsServerRequest req, AbsServerResponse resp, Map<String, Object> data) {
        handler.response(req, resp, data, segments, check);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{");
        if(segments != null) {
            sb.append("\"").append(PROPERTY_CHECK).append("\":[");
            int n = 0;
            for(ParameterInfo pi : segments) {
                if(n > 0) {
                    sb.append(',');
                }
                sb.append(pi.toString()).append('\n');
                n++;
            }
            sb.append("],");
        }
        sb.append("\"").append(PROPERTY_CHECK).append("\":")
          .append(check).append(',');
        sb.append("}");
        return sb.toString();
    }
    
    interface IResponseHandler {
        void response(AbsServerRequest req, AbsServerResponse resp,
                Map<String, Object> data, ParameterInfo[] segments, boolean check);
        boolean parse(ServiceInfo si, Set<String> params, Map<String, Object> cfg);
    }

    static class JsonResponseHandler implements IResponseHandler {
        /**
         * 检查和转换响应字段是否正常，可以配置不检查
         * @param req 请求信息
         * @param resp 响应信息
         * @param data 不接受空值
         * @param segments 响应字段，如果为空表示不处理，原样返回，长度为0表示删除所有响应
         * @param check 是否检查，false表示原样返回，segments只用于阅读
         */
        @Override
        public void response(AbsServerRequest req, AbsServerResponse resp,
                Map<String, Object> data, ParameterInfo[] segments, boolean check) {
            HandleResult hr = filterData(req, data, segments, check);
            resp.end(hr);
        }
        
        protected HandleResult filterData(AbsServerRequest req, Map<String, Object> data,
                ParameterInfo[] segments, boolean check) {
            if(segments == null || !check) {//没有定义任何返回，则不对data做任何转译操作
                return new HandleResult(RetCode.OK, data); 
            }

            if(segments.length == 0) {
                return HandleResult.OK; //忽略全部响应内容
            }
            /*
             * 无论返回内容中有什么内容，最终返回的只有response中定义的部分。
             * 响应中有json类型字段的，必须设置check为true，否则无法将字符串转为json
             */
            Map<String, Object> filteredData = new HashMap<>();
            for(ParameterInfo pi : segments) {
                Value v = pi.valueOf(req, data, data);
                if (!v.ok) {
                    LOG.error("Invalid reponse segment {},errInfo:{}, in {}", pi.name(), v.errInfo, req.uri);
                    LOG.debug("Response data {}", data);
                    return new HandleResult(RetCode.DATA_WRONG, pi.name() + " error"); 
                }
                filteredData.put(pi.name(), v.v);
            }
            return new HandleResult(RetCode.OK, filteredData);        
        }

        @Override
        public boolean parse(ServiceInfo si, Set<String> params, Map<String, Object> cfg) {
            return true;
        }
    }

    static class TemplateResponseHandler extends JsonResponseHandler implements IThreadPool {
        private File template; //模板存放路径，路径相对于服务根目录
        private boolean isZip;
        private ScriptElement[] saveAs; //下载后保存为的默认文件名
        private final String contentType; //响应的内容类型
        private final String outputExt; //输出文件扩展名
        private final String filterExts; //需要过滤的文件扩展名，多个用逗号分隔

        public TemplateResponseHandler(String outputExt, String filterExts) {
            this.outputExt = outputExt;
            this.contentType = AbsFileMethod.getContentType(outputExt);
            this.filterExts = filterExts;
        }
        
        @Override
        public void response(AbsServerRequest req, AbsServerResponse resp,
                Map<String, Object> data, ParameterInfo[] segments, boolean check) {
            //不调用filterData，一是数量通常较大，二是在模板中可以用js校正
            BigFileReader dataFile;
            String tempDir;
            File tmpDstFile;
            
            try {
                tempDir = AbsPlatform.createTempDirectory("zj_tmp_").getCanonicalPath();
                String name = StringUtil.uuid();
                
                String outputRootDir = FileUtil.addPath(tempDir, name);
                req.put(PROPERTY_ROOTDIR, outputRootDir);
                
                tmpDstFile = new File(outputRootDir + "." + this.outputExt);
                if(this.isZip) {
                    FileUtil.unzipFile(this.template, outputRootDir);
                    fillEachFile(outputRootDir, req, data);
                    FileUtil.zipDir(tmpDstFile, outputRootDir);
                } else {
                    FileUtil.copyFile(this.template, tmpDstFile);
                    fillOneFile(tmpDstFile, req, data);
                }
                dataFile = new BigFileReader(tmpDstFile);
            } catch (IOException ioe) {
                resp.error(HttpResponseStatus.INTERNAL_SERVER_ERROR, RetCode.INTERNAL_ERROR);
                LOG.error("Fail to translate template file {}", this.template, ioe);
                return;
            }
            LOG.debug("Send `{}`", tmpDstFile);
            resp.putHeader(HttpUtil.HEAD_CONTENT_TYPE, this.contentType);
            
            String saveAs = ScriptElement.runAll(this.saveAs, req, data);
            String n = UrlPathInfo.urlEncode(saveAs); //因为安卓版本问题，不可以使用UrlEncoder
            resp.putHeader(HttpUtil.HEAD_ATTACHMENT, "attachment;filename=" + n);

            resp.sendChunkedFile(dataFile).whenCompleteAsync((result, ex) -> {
                if(ex != null) {
                    LOG.error("Fail to send file {}", tmpDstFile, ex);
                    resp.error(HttpResponseStatus.INTERNAL_SERVER_ERROR, RetCode.INTERNAL_ERROR);
                } else if(LOG.isDebugEnabled()) {
                    LOG.debug("Send `{}`, result:{}", tmpDstFile, result);
                }
                dataFile.close();
                FileUtil.removeDir(new File(tempDir));
            }, Pool);
        }
        
        private void fillEachFile(String zipDir, AbsServerRequest req, Map<String, Object> data) throws IOException {
            File[] files = new File(zipDir).listFiles();
            if(files == null || files.length == 0) {
                return;
            }
            String ext;
            
            for(File f : files) {
                if(f.isDirectory()) {
                    fillEachFile(f.getCanonicalPath(), req, data);
                } else {
                    ext = FileUtil.getFileExtension(f.getName());
                    if(this.filterExts.contains(ext)) {
                        fillOneFile(f, req, data);
                    }
                }
            }
        }
        
        //将文件中的占位符翻译成实际的内容，然后存文件
        private boolean fillOneFile(File f, AbsServerRequest req, Map<String, Object> data) { 
            String s = FileUtil.readFile(f, IConst.DEFAULT_CHARSET);
            char ch;
            int i;
            
            for(i = 0; i < s.length(); i++) {
                ch = s.charAt(i);
                if(ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') {
                    break; //跳过前面的空行、空格
                }
            }
            
            boolean isJs = false;
            if(s.indexOf(IConst.JS_HEAD, i) >= i) {
                s = s.substring(i + IConst.JS_HEAD.length());
                isJs = true;
            }

            ScriptElement[] eles = ScriptElement.parsePlaceHolder(s, req.params().keySet(), IConst.EMPTY_STR, "\"");
            String formatedStr;
            if(eles != null) {
                String result = ScriptElement.runAll(eles, req, data);
                formatedStr = isJs ? JsEngine.getString(result) : result;
            } else {
                formatedStr = s;
            }
            return FileUtil.writeFile(f, formatedStr, IConst.DEFAULT_CHARSET);
        }
        
        @Override
        public boolean parse(ServiceInfo si, Set<String> params, Map<String, Object> cfg) {
            String template = ValParser.getAsStr(cfg, PROPERTY_TEMPLATE);
            if(StringUtil.isEmpty(template)) {
                LOG.error("Null {} config in '{}'", PROPERTY_TEMPLATE, si.name);
                return false;
            }
            String tmplName = FileUtil.addPath(si.homeDir, template);
            this.template = new File(tmplName);
            LOG.debug("Load template file {}", tmplName);
            if(!this.template.exists()) {
                LOG.error("Invalid {} config in '{}', {} not exists", PROPERTY_TEMPLATE, si.name, tmplName);
                return false;
            }
            this.isZip = "zip".equalsIgnoreCase(FileUtil.getFileExtension(template));
            if(!this.isZip) { //如果不是zip则表示为可直接解析的模板文件
                this.isZip = ValParser.getAsBool(cfg, PROPERTY_ISZIP);
            }

            String saveAs = ValParser.getAsStr(cfg, PROPERTY_SAVEAS);
            if(!FileUtil.getFileExtension(saveAs).equals(this.outputExt)) {
                saveAs += "." + this.outputExt;
            }
            
            this.saveAs = ScriptElement.parsePlaceHolder(saveAs, params, IConst.EMPTY_STR, "\"");
            
            return true;
        }
    }
    
    static class DocxResponseHandler extends TemplateResponseHandler {
        public DocxResponseHandler() {
            super("docx", "xml");
        }
    }
    
    /**
     * 模板中不支持sharedStrings.xml，且只支持一个sheet，即sheet1.xml
     * sheet1.sheetData中每行的每个单元不支持t="s"（否则要使用sharedStrings）
     * <row r="4" ht="15.75" customHeight="1" spans="1:7">
     *  <c r="A1" s="7"><v>1</v></c>
     *  <c r="B1" s="9" （t="s"不可以有）><v>7</v></c>
     * </row>
     */
    static class XlsxResponseHandler extends TemplateResponseHandler {
        public XlsxResponseHandler() {
            super("xlsx", "xml");
        }
    }
    
    static class TextResponseHandler extends TemplateResponseHandler {
        public TextResponseHandler() {
            super("txt", "txt");
        }
    }

    private static final IResponseHandler DEFAUTL_HANDLER = new JsonResponseHandler();
}
