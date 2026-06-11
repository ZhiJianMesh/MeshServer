package cn.net.zhijian.mesh.frm.process;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
import cn.net.zhijian.util.FifoCache;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

import org.slf4j.Logger;

/**
 * 文件上传处理器
 * 使用json体分片上传文件内容，实现类似断点续传的能力。
 * 只能在一个实例上运行，多个实例无法同步上传状态，也无法合并文件
 * @author flyinmind@zhijian.net.cn
 */
public class UploadProcessor extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    private static final int PKG_SIZE = 15 * 1024;
    private static final int MAX_CACHE_TIME = 5 * 60 * 1000;
    private static final int MAX_FILE_NUM = 50;
    
    private static final String CFG_PATH = "path"; //上传后，文件存放路径
    
    private static final String R_START = "start";
    private static final String R_FILENO = "fileNo";
    private static final String REQ_CONTENT = "content";
    
    private static final String REQ_FILESIZE = "size";
    private static final String REQ_FILENAME = "name";
    private static final String REQ_DIGEST = "digest";
    private static final String RESP_PKGSIZE = "pkgSize";

    /*
     * 正在上传中的文件信息，最多可以有MAX_FILE_NUM个文件同时上传。
     * 超过MAX_FILE_NUM个，最老的会在第MAX_FILE_NUM+1个上传开始时失效，
     * 文件会被关闭，缓存信息会被清除
     */
    private static final FifoCache<String, FileWriter> Files = new FifoCache<>(MAX_CACHE_TIME, MAX_FILE_NUM);
    
    private ScriptElement[] path; //存放文件的目录，只能存在服务自己的根目录下
    
    public UploadProcessor(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        Map<String, Object> params = req.params();
        String fileNo = ValParser.getAsStr(params, R_FILENO);
        if(!StringUtil.isEmpty(fileNo)) { //没有文件号，表示是第一个请求
            FileWriter fw = Files.get(fileNo);
            if(fw == null) {
                return HandleResult.future(RetCode.NOT_EXISTS, "not exists");
            }

            int start = ValParser.getAsInt(params, R_START);
            String content = ValParser.getAsStr(params, REQ_CONTENT);
            Map<String, Object> res = MapBuilder.of(R_START, start);
            try {
                fw.save(start, content);
                if(fw.over()) {
                    if(fw.check()) {
                        res.put(HandleResult.CODE, RetCode.OK);
                        res.put(HandleResult.INFO, "OVER");
                        res.put(REQ_FILESIZE, fw.size);
                        res.put(REQ_DIGEST, fw.digest);
                        over(req, res, fw);
                    } else {
                        res.put(HandleResult.CODE, RetCode.DATA_WRONG);
                        res.put(HandleResult.INFO, "fail to check digest");
                    }
                    Files.remove(fileNo);
                } else {
                    res.put(HandleResult.CODE, RetCode.OK);
                    res.put(HandleResult.INFO, RetCode.INFO_SUCCESS);
                }
                return HandleResult.future(res);
            } catch (IOException e) {
                LOG.error("Fail to write file", e);
                return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to write file");
            }
        }
        
        String name = ValParser.getAsStr(params, REQ_FILENAME);
        if(name.contains("./")) {//不容许相对路径
            return HandleResult.future(RetCode.WRONG_PARAMETER, "invalid file name,contains relative path");
        }
        
        int fileSize = ValParser.getAsInt(params, REQ_FILESIZE);
        String digest = ValParser.getAsStr(params, REQ_DIGEST);
        String path = translateElements(this.path, req, resp);
        FileUtil.createDir(path);
        File file = new File(FileUtil.addPath(path, name));

        fileNo = StringUtil.base64UUID(); //使用随机ID，降低可预测性，减少被攻击风险
        try {
            FileWriter fr = new FileWriter(fileSize, file, digest);
            Files.put(fileNo, fr); //第一个请求，不携带文件内容
            return HandleResult.future(MapBuilder.of(R_FILENO, fileNo, RESP_PKGSIZE, PKG_SIZE));
        } catch (FileNotFoundException e) {
            LOG.error("Fail to create file record,{}", file, e);
            return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to create file");
        }
    }

    protected void over(AbsServerRequest req, Map<String, Object> resp, FileWriter fw) {
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
    
    public static class FileWriter implements Closeable {
        public final File fp;
        public final int pkgNum;
        public final String digest; //摘要
        public final int size; //最大2G
        
        final int[] flags; //每一位表示一个分包是否上传完毕
        RandomAccessFile file;
        int uploadedNum = 0;
        
        FileWriter(int fileSize, File file, String digest) throws FileNotFoundException {
            this.size = fileSize;
            this.pkgNum = (int)Math.ceil((double)fileSize / PKG_SIZE);
            this.digest = digest;
            int flagLen = (this.pkgNum >> 5);
            if((this.pkgNum & 0x1f) != 0) { //除32有余数，则加一个int
                flagLen++;
            }
            File parent = file.getParentFile();
            if(parent != null && !parent.exists()) {
                FileUtil.createDir(parent);
            }
            this.flags = new int[flagLen];
            this.fp = file;
            this.file = new RandomAccessFile(file, "rwd");
        }

        void save(int start, String content) throws IOException {
            synchronized(this) { //同一时间只能有一个线程写数据
                int pkgNo = start / PKG_SIZE;
                int n = (pkgNo >> 5); //每个int标识32个分片
                int bit = (0x01 << (pkgNo & 0x1f)); //分片号除余32
                if ((flags[n] & bit) != 0) {
                    throw new IOException("repeatedly upload package,start(" + start + ")");
                }
                this.flags[n] |= bit;
                this.file.seek(start);
                byte[] b = ByteUtil.base642bin(content);
                if (b.length > PKG_SIZE) {
                    throw new IOException("exceed the pkg size");
                }
                if(start + b.length > size) {
                    throw new IOException("exceed the file size(" + size + "),start:" + start);
                }
                this.file.write(b);
                this.uploadedNum++;
                if (this.uploadedNum >= this.pkgNum) {
                    int realUploadedNum = 0;
                    for (int flg : this.flags) {
                        realUploadedNum += Integer.bitCount(flg);
                    }
                    this.uploadedNum = realUploadedNum; //修正一次上传分片数
                }
            }
        }
        
        boolean over() {
            return this.uploadedNum == this.pkgNum;
        }
        
        boolean check() throws IOException {
            close();
            String digest = FileUtil.digest(this.fp);
            return this.digest.equals(digest);
        }
        
        @Override
        public void close() throws IOException {
            synchronized(this) {
                if(this.file != null) {
                    this.file.close();
                    this.file = null;
                }
            }
        }
    }
}
