package cn.net.zhijian.mesh.frm.config.aclchecker;

import java.util.ArrayList;
import java.util.List;

import cn.net.zhijian.mesh.frm.config.ApiInfo;
import cn.net.zhijian.util.StringUtil;

class FeatureRight {
    private static final String ALL_RIGHTS = "*";

    //role-cls->rights(feature1,feature2,...)
    //因为绝大部分情况，只有一两个，所以直接用字符串数组，而不用Set
    private final String[] rights;

    FeatureRight(String allowedFeatures) {
        String[] ss = allowedFeatures.split(",");
        List<String> rights = new ArrayList<>();

        for(String s : ss) {
            if(!StringUtil.isEmpty(s)) {
                if(ALL_RIGHTS.equals(s)) {
                    rights = null; //saves little memory :)
                    break;
                }
                rights.add(s.trim());
            }
        }
        if(rights == null) {
            this.rights = null;
            return;
        }
        this.rights = rights.toArray(new String[]{});
    }

    public boolean hasRight(ApiInfo ai) {
        if(ai.feature == null || this.rights == null) {
            return true;
        }
        for(String s : this.rights) {
            if(s.equals(ai.feature)) {
                return true;
            }
        }
        return false;
    }
}
