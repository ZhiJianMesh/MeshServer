package cn.net.zhijian.mesh.frm.abs;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;
import cn.net.zhijian.mesh.frm.abs.AbsAssets.AssetType;
import cn.net.zhijian.util.FileUtil;

public class AbsAssetsTest extends UnitTestBase {
    @Test
    public void testReadFile() {
        try {
            InputStream is = AbsAssets.instance().open("logback.xml");
            assertTrue(is != null);
            byte[] s = FileUtil.readStream(is);
            assertTrue(s.length > 0);
        } catch(IOException e) {
            fail(e.getMessage());
        }
    }
    
    @Test
    public void testListFile() {
        try {
            String[] ff = AbsAssets.instance().list("");
            assertTrue(ff != null && ff.length > 0);
        } catch(IOException e) {
            fail(e.getMessage());
        }
    }
    
    @Test
    public void testJudgeFileType() {
        AssetType tp = AbsAssets.instance().fileType("META-INF");
        assertEquals(tp, AssetType.Directory);
        tp = AbsAssets.instance().fileType("channel.cfg");
        assertEquals(tp, AssetType.File);
        tp = AbsAssets.instance().fileType("channel1.cfg");
        assertEquals(tp, AssetType.NotExists);
    }
}
