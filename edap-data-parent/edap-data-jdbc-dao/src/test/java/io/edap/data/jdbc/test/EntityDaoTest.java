package io.edap.data.jdbc.test;

import io.edap.data.JdbcDaoRegister;
import io.edap.data.JdbcEntityDao;
import io.edap.data.jdbc.test.entity.Demo;
import org.h2.tools.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EntityDaoTest {

    private static Server server;

    @BeforeAll
    public static void init() throws SQLException {
        String[] args = new String[]{};
        server = Server.createTcpServer(args);
        server.start();
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.
                getConnection("jdbc:h2:~/test", "sa", "");
    }

    @Test
    public void testInsert() {
        String createSql = "create table demo \n" +
                "( \n" +
                "id bigint primary key not null AUTO_INCREMENT, \n" +
                "age varchar(200) null, \n" +
                "create_time bigint, \n" +
                "local_date_time bigint" +
                ")";
        try {
            Connection con = openConnection();
            System.out.println("connect success");
            boolean dropResult = false;
            try {
                con.createStatement().execute("drop table IF EXISTS demo");
                dropResult = true;
            } catch (Exception e) {
                e.printStackTrace();
            }
            boolean result = false;
            try {
                con.createStatement().execute(createSql);
                result = true;
            } catch (Exception e) {
                e.printStackTrace();
            }
            assertTrue(result);
            JdbcEntityDao<Demo> dao = JdbcDaoRegister.instance().getEntityDao(Demo.class, "h2");
            dao.setConnection(con);
            Demo demo = new Demo();
            demo.setId(1);
            demo.setCreateTime(System.currentTimeMillis());
            demo.setField1("23");
            demo.setLocalDateTime(System.currentTimeMillis());

            int row = dao.insert(demo);
            assertEquals(row > 0, true);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void close(Connection con) {
        if (con != null) {
            try {
                con.close();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @AfterAll
    public static void teardown() {
        if (server != null) {
            server.stop();
        }
    }
}
