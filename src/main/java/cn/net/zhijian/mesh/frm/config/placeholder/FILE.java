package cn.net.zhijian.mesh.frm.config.placeholder;

import java.io.File;
import java.security.InvalidParameterException;
import java.util.Map;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.StringUtil;

/**
 * 输出文件，存入一个路径，只能作为响应结果
 * 如果不存文件，使用此占位符没有意义，直接将响应当字符串处理即可。
 * `@{FILE|para,saveAs,rootDirName,base64}`
 * @author flyinmind of csdn.net
 *
 */
class FILE extends ScriptElement {
    private static final String DEFAULT_ROOT_PARA_NAME = "root_dir";
    boolean base64 = false;
    
    public FILE(String paras/* para,path[,root_para_name,base64] */,
            EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        String[] ss = StringUtil.split(paras, ApiParaHolder.PARA_SEPARATOR, ApiParaHolder.QUOTATION_MARK, true);
        if(ss.length < 2) {
            throw new InvalidParameterException("invalid FILE config");
        }
        this.paras = new ApiParaHolder[3];
        this.paras[0] = ApiParaHolder.parse(ss[0]); //paraName
        this.paras[1] = ApiParaHolder.parse(ss[1]); // filePath

        if(ss.length > 2) { //rootDir
            this.paras[2] = ApiParaHolder.parse(ss[2]);
        } else {
            this.paras[2] = ApiParaHolder.parse(DEFAULT_ROOT_PARA_NAME);
        }
        
        if(ss.length > 3 && ss[3].equalsIgnoreCase("base64")) {
            this.base64 = true;
        }
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        String file = this.paras[1].getAsString(req, resp);
        String root = this.paras[2].getAsString(req, resp);
        String saveAs = FileUtil.addPath(root, file);

        byte[] data;
        String v = paras[0].getAsString(req, resp);
        if(base64) {
            data = ByteUtil.stdBase64Decode(v);
        } else {
            data = v.getBytes(IConst.DEFAULT_CHARSET);
        }
        FileUtil.writeFile(new File(saveAs), data);
        return IConst.EMPTY_STR;
    }
}
