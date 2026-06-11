package cn.net.zhijian.mesh.bean;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.util.ByteUtil;
import cn.net.zhijian.util.StringUtil;

public class RootKeystoreTest extends UnitTestBase {
    private static final byte[] PLAIN = "==--test123789;;...".getBytes();
    
    public RootKeystoreTest() throws Exception {
        RootKeystore.init();
    }
    
    @Test
    public void testEncodeDecode() throws Exception {
        String txt = "abckkkk啦啦啦";
        String cipher = RootKeystore.instance().encode(txt);
        String plain = RootKeystore.instance().decode(cipher);
        assertEquals(plain, txt);
    }
    
    @Test
    public void testKeyStoreEnDeCodeAndParse() throws Exception {
        String pwd = StringUtil.base64UUID();
        String ks = RootKeystore.instance().encodeKeyStore(pwd);
        RootKeystore rks = RootKeystore.parse(ks, pwd);

        String txt = "我是汉献帝";
        String cipher = rks.encode(txt);
        String plain = rks.decode(cipher);
        assertEquals(plain, txt);
    }
    
    @Test
    public void testKeyStoreCreate() throws Exception {
        RootKeystore rks = RootKeystore.create();

        String txt = "你好，我是曹阿满";
        String cipher = rks.encode(txt);
        String plain = rks.decode(cipher);
        assertEquals(plain, txt);
    }

    @Test
    public void testKeyStoreBinary() throws Exception {
        byte[] ks = RootKeystore.instance().getBinaryKey();
        RootKeystore.init(ks);
        
        String txt = "你好，我是袁本初";
        String cipher = RootKeystore.instance().encode(txt);
        String plain = RootKeystore.instance().decode(cipher);
        assertEquals(plain, txt);
    }
    
    @Test
    public void testGetKey() {
        byte[] encoded1 = null;
        try {
            byte[] keyCode = RootKeystore.instance().getBinaryKey();
            System.out.println("root key:" + ByteUtil.bin2hex(keyCode));
            assertTrue(keyCode != null && keyCode.length > 0);
            encoded1 = RootKeystore.instance().encode(PLAIN);
            
            byte[] plain = RootKeystore.instance().decode(encoded1);
            assertTrue(Arrays.equals(PLAIN, plain));
            plain = RootKeystore.decode(keyCode, encoded1);
            assertTrue(Arrays.equals(PLAIN, plain));
        } catch (Exception e) {
            fail(e.getLocalizedMessage());
        }
    }
}
