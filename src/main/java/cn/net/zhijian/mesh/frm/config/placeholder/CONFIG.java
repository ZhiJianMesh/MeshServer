package cn.net.zhijian.mesh.frm.config.placeholder;

import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import cn.net.zhijian.mesh.bean.ApiParaHolder;
import cn.net.zhijian.mesh.client.BiosClient;
import cn.net.zhijian.mesh.frm.abs.AbsServerRequest;
import cn.net.zhijian.util.LogUtil;

/**
 * `@{CONFIG|configItem}`
 * @author flyinmind of csdn.net
 *
 */
final class CONFIG extends ScriptElement {
    private static final Logger LOG = LogUtil.getInstance();
    private static final String CFG_PARAMETER  = "para_";
    private final String configItem;

    public CONFIG(String paras/*keyName,parameter*/, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
        this.configItem = CFG_PARAMETER + ApiParaHolder.takeStr(paras);
    }

    @Override
    public Object run(AbsServerRequest req, Map<String, Object> resp) {
        try {
            String cfgVal = BiosClient.getConfig(req.serviceInfo(), this.configItem).get(5, TimeUnit.SECONDS);
            return convertQuotes(cfgVal);
        } catch (Exception e) {
            LOG.error("Fail to get config item {}", this.configItem, e);
            return this.configItem; //返回配置项名称
        }
    }
}
