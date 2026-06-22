package cn.net.zhijian.mesh.frm.process;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class RDBProcessorTest {
    @Test
    public void testFindKeywords() {
        String sql = "js:if(@[!pkgNum]>0){\n"
                + "      DB.sql(\"update pkgreports set logVal=@{val},logNum=logNum+1,orderBal=@[!total]\n"
                + "       where pkgId=@{!pkgId} and reportAt=@{NOW|unit86400000}\")\n"
                + "   }";
        char[][] kws = new char[][] {"update".toCharArray(), "insert".toCharArray(), "delete".toCharArray()};
        int pos = RDBProcessor.findSqlKeyWords(sql, kws);
        assertTrue(pos > 0);
        sql = "js:if(@[!pkgNum]>0){\n"
                + "      DB.sql(`insert into pkgreports(pkgId,reportAt,logVal,logNum,orderBal)\n"
                + "        values(@{!pkgId},@{NOW|unit86400000},@{val},@[!total])`\n"
                + "}";
        pos = RDBProcessor.findSqlKeyWords(sql, kws);
        assertTrue(pos > 0);
        sql = "js:if(@[!pkgNum]>0){\n"
            + "    DB.sql('delete from pkgreports where pkgId=@{!pkgId}"
            + "}";
        pos = RDBProcessor.findSqlKeyWords(sql, kws);
        assertTrue(pos > 0);
        sql = "js:if(@[!pkgNum]>0){\n"
                + "    DB.sql('select * from pkgreports where pkgId=@{!pkgId}"
                + "}";
        pos = RDBProcessor.findSqlKeyWords(sql, kws);
        assertTrue(pos < 0);
    }
}
