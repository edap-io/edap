package io.edap.data.jdbc.test;

import io.edap.data.JdbcDaoRegister;
import io.edap.data.JdbcEntityDao;
import io.edap.data.jdbc.test.entity.Demo;
import io.edap.data.jdbc.test.entity.DemoIntId;
import io.edap.data.jdbc.test.entity.DemoLongId;
import io.edap.data.jdbc.test.entity.DemoLongObjId;
import org.junit.jupiter.api.Test;

public class EntityDaoGeneratorTest {

    @Test
    public void testEntityDaoGenarate() {
        JdbcEntityDao<Demo> demoDao = JdbcDaoRegister.instance()
                .getEntityDao(Demo.class, "Postgresql");

        JdbcEntityDao<DemoIntId> demoIntIdDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoIntId.class, "Postgresql");

        JdbcEntityDao<DemoLongId> demoILongIdDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoLongId.class, "Postgresql");

        JdbcEntityDao<DemoLongObjId> demoILongObjIdDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoLongObjId.class, "Postgresql");
    }
}
