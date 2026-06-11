package cn.net.zhijian.mesh.builtin.keystore;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.AccessToken;
import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.DBClient;
import cn.net.zhijian.mesh.client.DBClient.DBReqBuilder;
import cn.net.zhijian.mesh.client.DBClient.SQLAction;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsRDBWorker;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IDBConst;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.mesh.frm.intf.IServiceWatcher;
import cn.net.zhijian.util.Ecc.EccKeyPair;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * @author flyinmind of csdn.net
 * 从bios取datarootkey，并存到keypair中，
 * KeystoreClient在处理时，从keystore中获取datarootkey，用于加解密数据密码
 */
public final class Watcher extends IServiceWatcher.DefaultServiceWatcher {
    private static final Logger LOG = LogUtil.getInstance();

    @Override
    public CompletableFuture<HandleResult> afterLoad(IServiceServer server, ServiceInfo si, String comPwd) {
        CompletableFuture<HandleResult> cf = super.afterLoad(server, si, comPwd);
        if(StringUtil.isEmpty(comPwd)) {
            LOG.debug("No company pwd, just init {} simply", si.name);
            return cf;
        }
        return cf.thenComposeAsync(hr -> {
            if(hr.code != RetCode.OK) {
                return HandleResult.future(hr);
            }

            return initDataRootKey(si, comPwd);
        }, Pool);
    }

    private CompletableFuture<HandleResult> initDataRootKey(ServiceInfo si, String comPwd) {
        CompanyInfo ci = CompanyInfo.instance();
        if(ci.isRoot()) { //根环境，datarootkey也存在keystore中，但是作为本地服务直接内部访问
            return HandleResult.future();
        }
        
        return KeyStoreBase.getDataRootKey(ci.id, si).thenComposeAsync(kp -> {
            if(kp != null) {
                return HandleResult.future();
            }
            
            AccessToken at = ci.adminToken(SERVICE_KEYSTORE);
            //必须用公司密码，因为数据根密钥对是使用公司密码加密的
            //从至简网格中查询公司数据根密钥，并存入keypair表
            //KeyStoreClient会从中获取，用于加解密数据密钥
            LOG.debug("Get data root key from cloud for company {}", ci.id);
            return ci.getRootKey(at.generate(), CompanyInfo.DATAROOT_INPRIVATE).thenComposeAsync(hr -> {
                if(hr.code != RetCode.OK) {
                    LOG.error("Fail to get {}'s data root key of company {},result:{}", si.name, ci.id, hr.brief());
                    return HandleResult.future(hr);
                }
                String dataRootKp = ValParser.getAsStr(hr.data, "kp");
                EccKeyPair eccDataKp;

                try {
                    eccDataKp = EccKeyPair.parse(dataRootKp, comPwd);
                    dataRootKp = eccDataKp.toString();//解密后的数据根密钥
                } catch(Exception e) {
                    LOG.error("Fail to parse keypair of company {}", ci.id, e);
                    return HandleResult.future(RetCode.DATA_WRONG, "invalid keypair from cloud");
                }

                //不可以调用keystore.company.putcdr，因为keystore服务还未启动完毕
                return KeyStoreBase.encodeDataRootkey(si, dataRootKp).thenComposeAsync(encKey -> {
                    if(StringUtil.isEmpty(encKey)) {
                        LOG.error("Fail to encode data root key in {}.afterLoad({})", si.name, ci.id);
                        return HandleResult.future(RetCode.INTERNAL_ERROR, "fail to encode data root key");
                    }
                    DBReqBuilder builder = new DBReqBuilder(si, "keystore", IDBConst.LOCAL_DBNO)
                            .readSlave(false);
                    builder.traceId("keystore_init_dr").cid(ci.id)
                           .put(IDBConst.DB_REQ_TIME, System.currentTimeMillis());
                    
                    String sql = AbsRDBWorker.modifyDMLSql("replace into keypair(cid,type,kp) values("
                            + ci.id + ",'CDR','" + encKey + "')", Long.toString(System.currentTimeMillis()));
                    SQLAction[] acts = new SQLAction[] {
                        SQLAction.create("save_datarootkey")
                        //按理type应为PDR，为了兼容KeyStoreBase.requestKeyPair
                        //仍然使用CDR类型，此处密钥对不加密存储
                        .sql(sql)
                    };
                    return DBClient.rdbRequest(builder, acts);                    
                }, Pool);
            });
        });
    }
}
