package cn.net.zhijian.mesh.frm.config.tokenchecker;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.mesh.frm.abs.AbsTokenChecker;
import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;

/**
 * 既可以用OM签名访问，也可以使用公司私钥签名访问，或临时访问口令签名访问
 */
class MNT extends AbsTokenChecker {
    final APP omTokenChecker;
    final BACKEND backendTokenChecker;
    
    MNT(APP om, BACKEND backend) {
        super(MNT_TOKEN_CHECKER);
        this.omTokenChecker = om;
        this.backendTokenChecker = backend;
    }

    @Override
    public CompletableFuture<AccessToken> check(AbsServerRequest req, String token) {
        //支持app(调用自己)、om、company、临时接入码四种
        int signType = AccessToken.getSignType(token);
        if(signType == SIGNTYPE_OMKEY) {
            return omTokenChecker.check(req, token);
        }
        return backendTokenChecker.check(req, token);
    }
    
    @Override
    public void addAvailables(ApiInfo ai) {
        super.addAvailables(ai);
        ai.addExtPara(IConst.EMBEDED_TOKEN_CID);
        ai.addExtPara(IConst.EMBEDED_TOKEN_ACC);
    }

    @Override
    public void addParas(Map<String, Object> params, AccessToken at) {
        super.addParas(params, at);
        int pos = at.ext.indexOf(AccessToken.EXT_SEPARATOR); //account,cid
        if(pos > 0) {
            params.put(IConst.EMBEDED_TOKEN_ACC, at.ext.substring(0, pos));
            params.put(IConst.EMBEDED_TOKEN_CID, at.ext.substring(pos + 1));
        } else {
            params.put(IConst.EMBEDED_TOKEN_ACC, at.ext);
        }
    }
}
