package cn.net.zhijian.mesh.builtin.verifycode;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.DBClient;
import cn.net.zhijian.mesh.client.DBClient.DBReqBuilder;
import cn.net.zhijian.mesh.client.DBClient.SQLAction;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsProcessor;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.util.ImageUtil;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 产生验证码，验证码原文记录在数据库中，此库不支持分库，并且每次会删除5分钟前的残余数据，
 * 在提交确认时，必须携带session（id）
 * @author flyinmind of csdn.net
 *
 */
public final class Image extends AbsProcessor {
    private static final Logger LOG = LogUtil.getInstance();
    private static final int VERIFYCODE_LEN = 4;
    private static final String PARA_SESSION = "session";

    public Image(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        int w = req.getInt("w");
        int h = req.getInt("h");
        int l = ValParser.getAsInt(req.params(), "l", VERIFYCODE_LEN);

        String verifyCode = SecureUtil.genVisibleCode(l);
        long id = StringUtil.longHashCode(StringUtil.base64UUID());//不可以有规律，否则容易被攻击
        
        //verify服务只在云侧运行，所以使用local_dbno
        DBReqBuilder builder = new DBReqBuilder(serviceInfo(), "verify", IDBConst.LOCAL_DBNO)
                .readSlave(false);
        builder.traceId(req.traceId)
               .cid(req.cid())
               .put(IDBConst.DB_REQ_TIME, req.reqTime);
        return DBClient.rdbRequest(builder, new SQLAction[] {
            SQLAction.create("saveCode").needModify(true)
                .sql("replace into verify(id,code) values("
                    + id + ",'" + verifyCode.toLowerCase() + "')")
        }).thenComposeAsync((hr) -> {
            if(hr.code != RetCode.OK) {
                LOG.error("Fail to save verify code into db");
                return CompletableFuture.completedFuture(hr);
            }

            String strImg;
            try {
                strImg = ImageUtil.bufferedPngToBase64(ImageUtil.genVerifyCode(verifyCode, w, h));
            } catch (IOException e) {
                LOG.error("Fail to create image", e);
                return futureResult(HandleResult.InternalError);
            }
            /*
             * 在ApiMethod中，所有响应的data内容都会存入resp，
             * 这里直接存入resp，响应的data为空，减少一次putAll操作
             */
            resp.put("img", strImg);
            resp.put(PARA_SESSION, Long.toString(id));
            return futureResult();
        }, Pool);
    }
}