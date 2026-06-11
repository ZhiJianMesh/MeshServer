package cn.net.zhijian.mesh.frm.config.placeholder;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.ImageUtil;
import cn.net.zhijian.util.LogUtil;

/**
 * 输出base64图片文件，存入一个路径，只能作为响应结果。
 * `@{BASE64IMG|content,saveAs,rootDir[,base64]}`
 * rootDir/saveAs是完整的输出路径，如果未指定base64格式，则当作二进制输出到问界中
 * @author flyinmind of csdn.net
 *
 */
final class BASE64IMG extends FILE {
    private static final Logger LOG = LogUtil.getInstance();
    
    public BASE64IMG(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String file = this.paras[1].getAsString(req, resp); //saveAs
        String root = this.paras[2].getAsString(req, resp); //rootDir
        String saveAs = FileUtil.addPath(root, file);
        
        String v = paras[0].getAsString(req, resp); //content
        try {
            if(base64) {
                ImageUtil.base64ToImage(v, saveAs);
            } else {
                byte[] data = v.getBytes(IConst.DEFAULT_CHARSET);
                FileUtil.writeFile(new File(saveAs), data);
            }
        } catch (IOException e) {
            LOG.error("Fail to write `{}`", saveAs, e);
        }
        
        return IConst.EMPTY_STR;
    }
}
