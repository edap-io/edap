package io.edap.data.jdbc.test;

import io.edap.data.JdbcDaoRegister;
import io.edap.data.JdbcEntityDao;
import io.edap.data.JdbcViewDao;
import io.edap.data.jdbc.test.entity.Demo;
import io.edap.data.jdbc.test.entity.DemoIntId;
import io.edap.data.jdbc.test.entity.DemoLongId;
import io.edap.data.jdbc.test.entity.DemoLongObjId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class EntityDaoGeneratorTest {

    @Test
    public void testEntityDaoGenarate() {
        JdbcEntityDao<Demo> demoDao = JdbcDaoRegister.instance()
                .getEntityDao(Demo.class, "Postgresql");
        assertNotNull(demoDao);
        JdbcEntityDao<DemoIntId> demoIntIdDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoIntId.class, "Postgresql");
        assertNotNull(demoIntIdDao);
        JdbcEntityDao<DemoLongId> demoILongIdDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoLongId.class, "Postgresql");
        assertNotNull(demoILongIdDao);
        JdbcEntityDao<DemoLongObjId> demoILongObjIdDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoLongObjId.class, "Postgresql");
        assertNotNull(demoILongObjIdDao);
    }

    @Test
    public void testViewDaoGenarate() {
        JdbcViewDao<Demo> demoDao = JdbcDaoRegister.instance()
                .getViewDao(Demo.class, "Postgresql");
        assertNotNull(demoDao);
        JdbcViewDao<DemoIntId> demoIntIdDao = JdbcDaoRegister.instance()
                .getViewDao(DemoIntId.class, "Postgresql");
        assertNotNull(demoIntIdDao);
        JdbcViewDao<DemoLongId> demoILongIdDao = JdbcDaoRegister.instance()
                .getViewDao(DemoLongId.class, "Postgresql");
        assertNotNull(demoILongIdDao);
        JdbcViewDao<DemoLongObjId> demoILongObjIdDao = JdbcDaoRegister.instance()
                .getViewDao(DemoLongObjId.class, "Postgresql");
        assertNotNull(demoILongObjIdDao);
    }
}
