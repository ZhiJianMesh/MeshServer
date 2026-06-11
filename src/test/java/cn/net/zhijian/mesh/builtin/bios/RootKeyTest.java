package cn.net.zhijian.mesh.builtin.bios;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.bean.RootKeystore;

public class RootKeyTest extends UnitTestBase {
    private static final String PLAIN = "lllkdfdk3432510$$%%^";
    private static final byte[] PLAIN_BYTES = PLAIN.getBytes();
   
    public RootKeyTest() throws Exception {
        RootKeystore.init();
    }
    
    @Test
    public void testEncodeDecodeStr() {
        try {
            byte[] cipher = RootKeystore.instance().encode(PLAIN_BYTES);
            byte[] plain = RootKeystore.instance().decode(cipher);
            assertTrue(Arrays.equals(plain, PLAIN_BYTES));
        } catch (Exception e) {
            fail(e.getLocalizedMessage());
        }
    }

    @Test
    public void testEncodeDecodeBytes() {
        try {
            String cipher = RootKeystore.instance().encode(PLAIN);
            String plain = RootKeystore.instance().decode(cipher);
            assertEquals(PLAIN, plain);
        } catch (Exception e) {
            fail(e.getLocalizedMessage());
        }
    }
}
