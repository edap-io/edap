package io.edap.data.jdbc.test;

import io.edap.data.*;
import io.edap.data.jdbc.test.entity.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class EntityDaoGeneratorTest {

    @Test
    public void testEntityDaoGenarate() {
        JdbcEntityDao<Demo> demoDao = JdbcDaoRegister.instance()
                .getEntityDao(Demo.class, new DaoOption());
        assertNotNull(demoDao);
        JdbcEntityDao<DemoIntId> demoIntIdDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoIntId.class, new DaoOption());
        assertNotNull(demoIntIdDao);
        JdbcEntityDao<DemoLongId> demoILongIdDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoLongId.class, new DaoOption());
        assertNotNull(demoILongIdDao);
        JdbcEntityDao<DemoLongObjId> demoILongObjIdDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoLongObjId.class, new DaoOption());
        assertNotNull(demoILongObjIdDao);

        JdbcEntityDao<DemoAllType> demoAllTypeDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoAllType.class, new DaoOption());
        assertNotNull(demoAllTypeDao);

        JdbcEntityDao<DemoStringId> demoStringIdDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoStringId.class, new DaoOption());
        assertNotNull(demoStringIdDao);
    }

    @Test
    public void testViewDaoGenarate() {
        JdbcViewDao<Demo> demoDao = JdbcDaoRegister.instance()
                .getViewDao(Demo.class, new DaoOption());
        assertNotNull(demoDao);
        JdbcViewDao<DemoIntId> demoIntIdDao = JdbcDaoRegister.instance()
                .getViewDao(DemoIntId.class, new DaoOption());
        assertNotNull(demoIntIdDao);
        JdbcViewDao<DemoLongId> demoILongIdDao = JdbcDaoRegister.instance()
                .getViewDao(DemoLongId.class, new DaoOption());
        assertNotNull(demoILongIdDao);
        JdbcViewDao<DemoLongObjId> demoILongObjIdDao = JdbcDaoRegister.instance()
                .getViewDao(DemoLongObjId.class, new DaoOption());
        assertNotNull(demoILongObjIdDao);

        JdbcViewDao<DemoStringId> demoStringIdDao = JdbcDaoRegister.instance()
                .getViewDao(DemoStringId.class, new DaoOption());
        assertNotNull(demoStringIdDao);
    }

    @Test
    public void testMapGenerator() {
        JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
        assertNotNull(mapDao);
    }
}
