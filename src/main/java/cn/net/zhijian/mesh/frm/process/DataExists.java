package cn.net.zhijian.mesh.frm.process;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.RequestInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.MapBuilder;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;

/**
 * 判断数据是否存在，只支持select count(*) recNum from ...
 * @author flyinmind of csdn.net
 *
 */
public final class DataExists extends RDBProcessor {
    private static final Logger LOG = LogUtil.getInstance();

    private static final String CFG_SQL = "sql";
    private static final String CFG_EXPECT = "expect";
    private static final String CFG_NUMSEG = "numSeg";
    private static final String EXISTS_OR_NOT_SEG = "exists_or_not";
    private boolean expect; //是否期望数据存在
    private String numSeg; //计数字段名称
    private HandleResult errorResult;

    public DataExists(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }

    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        return super.handle(req, resp).thenApplyAsync(hr -> {
            if(hr.code == RetCode.OK) {
                if(hr.data == null) {
                    return new HandleResult(RetCode.NOT_EXISTS);
                }
                int num = ValParser.getAsInt(hr.data, this.numSeg, 0);
                hr.data.remove(this.numSeg); //不必返回
                if(this.expect) {
                    return num > 0 ? hr : errorResult;
                } else {
                    return num > 0 ? errorResult : hr;
                }
            }
            return hr;
        }, Pool);
    }
    
    @Override
    public boolean parse(UrlPathInfo url, Map<String, Object> cfg, RequestInfo request) {
        String sql = ValParser.getAsStr(cfg, CFG_SQL, IConst.EMPTY_STR);
        if(StringUtil.isEmpty(sql)) {
            LOG.error("{} not configured in {}", CFG_SQL, url);
            return false;
        }
        
        Map<String, Object> sqlCfg = MapBuilder.of(SQL_MULTI, false, SQL_MERGE, true, SQL_METAS, META_EACH);
        String numSeg = ValParser.getAsStr(cfg, CFG_NUMSEG);
        if(StringUtil.isEmpty(numSeg) || EXISTS_OR_NOT_SEG.equals(numSeg)) {
            numSeg = EXISTS_OR_NOT_SEG;
            sqlCfg.put(SQL_SQL, "select exists(" + sql + ") " + numSeg); //只要有一条存在即可，不必count全部
        } else {
            sqlCfg.put(SQL_SQL, sql);
        }
        this.numSeg = numSeg;
        if(!super.parse(url, cfg, request, Arrays.asList(sqlCfg))) {
            return false;
        }

        this.expect = ValParser.getAsBool(cfg, CFG_EXPECT, true);
        int errorCode;
        if(this.expect) {
            errorCode = RetCode.parseCode(cfg.get(CFG_ERROR_CODE), RetCode.NOT_EXISTS);
        } else {
            errorCode = RetCode.parseCode(cfg.get(CFG_ERROR_CODE), RetCode.EXISTS);
        }
        String errorInfo = ValParser.getAsStr(cfg, CFG_ERROR_INFO, RetCode.getInfo(errorCode));
        this.errorResult = new HandleResult(errorCode, errorInfo);
        return true;
    }
}