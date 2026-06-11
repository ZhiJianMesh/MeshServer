package cn.net.zhijian.mesh.frm.config.placeholder;

import cn.net.zhijian.mesh.bean.ApiParaHolder;

/**
 * `@{LIST|[!]paraName[.segName|col][,quote]}`
 * 解决NORMAL中数组自动加[]的问题，导致在sql中无法使用。
 * 将所有成员输出为一个列表，如果指定了字段名，则源数据必须为一个对象数组，
 * 且需包含该字段
 * @author flyinmind of csdn.net
 *
 */
abstract class AbsListElement extends ScriptElement {
    protected String seg;
    protected int col; //如果每行都是数组，可以指定列

    public AbsListElement(String paras, EleType type, String quote, String safeQuote) {
        super(paras, type, quote, safeQuote);
    }
    
    protected void parseListPara(String p) {
        int pos = p.indexOf('.');
        if(pos > 0) {
            this.seg = p.substring(pos + 1);
            if(this.seg.matches("^\\d+$")) { //是数字，表示列号
                this.col = Integer.parseInt(this.seg);
            } else {
                this.col = -1;
            }
            this.paras = ApiParaHolder.parseHolders(p.substring(0, pos));
        } else {
            this.paras = ApiParaHolder.parseHolders(p);
            this.seg = null;
            this.col = -1;
        }
    }
}
