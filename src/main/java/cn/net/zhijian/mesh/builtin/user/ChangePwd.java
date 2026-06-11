package cn.net.zhijian.mesh.builtin.user;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;
/**
 * 修改密码
 * 之所以不能用js替代，是因为这里需要更新缓存
 * @author flyinmind of csdn.net
 *
 */
public final class ChangePwd extends UserBase {
    private static final Logger LOG = LogUtil.getInstance();
    public ChangePwd(ServiceInfo serviceInfo, ApiInfo apiInfo, String processName) {
        super(serviceInfo, apiInfo, processName);
    }
    @Override
    protected CompletableFuture<HandleResult> handle(AbsServerRequest req, Map<String, Object> resp) {
        String pwdInDb = ValParser.getAsStr(resp, "pwd", null); //数据库中的密码
        if(StringUtil.isEmpty(pwdInDb)) {
            return futureResult(RetCode.NOT_EXISTS, "user not found");
        }
        resp.clear(); //清除上一步返回结果，不能传给调用方
        String oldPwd = req.getString("oldPassword");
        if(!SecureUtil.pbkdf2Check(oldPwd, pwdInDb)) { //判断原密码是否正确
            LOG.debug("Invalid oldPassword");
            return futureResult(RetCode.NOT_EXISTS, "user not found");
        }
        long cacheId = getCacheId(req.cid(), req.token().generate());
        UserTokenCache.remove(cacheId); //删除缓存中的token
        return super.handle(req, resp); //保存密码
    }
}