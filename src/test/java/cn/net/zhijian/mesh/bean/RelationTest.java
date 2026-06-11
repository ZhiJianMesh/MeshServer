package cn.net.zhijian.mesh.bean;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.UnitTestBase;

public class RelationTest extends UnitTestBase {
    @Test
    public void testStringRelation() {
        String rs = "s.==";
        int r = Relation.parseRelation(rs);
        assertTrue(!Relation.compare("a", "b", Relation.valType(r), Relation.relation(r)));
        rs = "s.!=";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare("a", "b", Relation.valType(r), Relation.relation(r)));
        rs = "s.>";
        r = Relation.parseRelation(rs);
        assertTrue(!Relation.compare("a", "b", Relation.valType(r), Relation.relation(r)));
        rs = "s.<";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare("a", "b", Relation.valType(r), Relation.relation(r)));
        rs = "s.<=";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare("a", "b", Relation.valType(r), Relation.relation(r)));
        rs = "s.@";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare("a", "b,a", Relation.valType(r), Relation.relation(r)));
        rs = "string.!@";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare("c", "b,a", Relation.valType(r), Relation.relation(r)));
        rs = "s.~";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare("123", "\\d+", Relation.valType(r), Relation.relation(r)));
        rs = "s.!~";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare("123", "[a-z]+", Relation.valType(r), Relation.relation(r)));
    }
    
    @Test
    public void testIntRelation() {
        String rs = "i.==";
        int r = Relation.parseRelation(rs);
        assertTrue(!Relation.compare(1, 2, Relation.valType(r), Relation.relation(r)));
        rs = "i.!=";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare(1, 2, Relation.valType(r), Relation.relation(r)));
        rs = "int.>";
        r = Relation.parseRelation(rs);
        assertTrue(!Relation.compare(1, 2, Relation.valType(r), Relation.relation(r)));
        rs = "i.<";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare(1, 2, Relation.valType(r), Relation.relation(r)));
        rs = "i.<=";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare(1, 1, Relation.valType(r), Relation.relation(r)));
    }
    
    @Test
    public void testObjectRelation() {
        String rs = "o.==";
        int r = Relation.parseRelation(rs);
        assertTrue(Relation.compare(null, null, Relation.valType(r), Relation.relation(r)));
        rs = "o.!=";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare("a", null, Relation.valType(r), Relation.relation(r)));
        rs = "o.==";
        r = Relation.parseRelation(rs);
        assertTrue(!Relation.compare("a", null, Relation.valType(r), Relation.relation(r)));
    }
    
    @Test
    public void testBooleanRelation() {
        String rs = "b.==";
        int r = Relation.parseRelation(rs);
        assertTrue(Relation.compare(true, true, Relation.valType(r), Relation.relation(r)));
        rs = "b.!=";
        r = Relation.parseRelation(rs);
        boolean b = false;
        assertTrue(Relation.compare(b, true, Relation.valType(r), Relation.relation(r)));
        rs = "b.==";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare(b, false, Relation.valType(r), Relation.relation(r)));
    }
    
    @Test
    public void testFloatRelation() {
        String rs = "f.==";
        int r = Relation.parseRelation(rs);
        assertTrue(!Relation.compare(1.0f, 2.2f, Relation.valType(r), Relation.relation(r)));
        rs = "float.!=";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare(1.0f, 2.2f, Relation.valType(r), Relation.relation(r)));
        rs = "f.>";
        r = Relation.parseRelation(rs);
        assertTrue(!Relation.compare(1.0f, 2.2f, Relation.valType(r), Relation.relation(r)));
        rs = "f.<";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare(1.0f, 2.2f, Relation.valType(r), Relation.relation(r)));
        rs = "f.<=";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare(1.0f, 1.0f, Relation.valType(r), Relation.relation(r)));
    }
    
    @Test
    public void testDoubleRelation() {
        String rs = "d.==";
        int r = Relation.parseRelation(rs);
        assertTrue(!Relation.compare(1.0, 2.2, Relation.valType(r), Relation.relation(r)));
        rs = "double.!=";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare(1.0, 2.2, Relation.valType(r), Relation.relation(r)));
        rs = "d.>";
        r = Relation.parseRelation(rs);
        assertTrue(!Relation.compare(1.0, 2.2, Relation.valType(r), Relation.relation(r)));
        rs = "d.<";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare(1.0, 2.2, Relation.valType(r), Relation.relation(r)));
        rs = "d.<=";
        r = Relation.parseRelation(rs);
        assertTrue(Relation.compare(1.0, 1.0, Relation.valType(r), Relation.relation(r)));
    }
}
