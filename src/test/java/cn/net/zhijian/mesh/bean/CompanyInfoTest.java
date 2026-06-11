package cn.net.zhijian.mesh.bean;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.MeshException;
import cn.net.zhijian.util.StringUtil;

public class CompanyInfoTest extends UnitTestBase {
    private void testCompanyInfo(CompanyInfo ci, String insideAddr) {
        assertNotNull(ci);
        assertNotNull(CompanyInfo.instance());
        assertTrue(ci.isValid());
        assertEquals(ci.id, 40);
        assertEquals(ci.area(), 0);
        assertEquals(ci.dbNo(), 0);
        assertTrue(!ci.isRoot());
        assertTrue(!StringUtil.isEmpty(ci.accessCode));
        assertEquals(insideAddr, ci.insideAddr);        
    }
    
    @Test
    public void testLoadPrimaryConfig() {
        CompanyInfo ci = CompanyInfo.instance();
        testCompanyInfo(ci, insideAddr);
    }
    
    @Test
    public void testGenerateAccessToken() {
        CompanyInfo ci = CompanyInfo.instance();
        String service = TEST_SERVICE;
        AccessToken at = ci.adminToken(service);
        assertNotNull(at);
        assertEquals(at.callee, service);
        assertEquals(at.caller, "1");
        assertEquals(at.tokenType(), AccessToken.TOKENTYPE_USER);
        assertEquals(at.signType(), AccessToken.SIGNTYPE_COMPANYKEY);
    }
    
    @Test
    public void testCompanyInfoSave() throws MeshException {
        CompanyInfo ci = CompanyInfo.instance();
        String outsideAddr = ci.outsideAddr();
        String addr = "1.1.1.1";
        ci.setOutsideAddr(addr);
        CompanyInfo.save();
        CompanyInfo ci1 = CompanyInfo.instance();
        CompanyInfo.reInit();
        testCompanyInfo(ci1, insideAddr);
        ci.setOutsideAddr(outsideAddr); //restore it
        CompanyInfo.save();
    }
}
