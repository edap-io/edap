package io.edap.data.jdbc.test;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.edap.data.DaoOption;
import io.edap.data.JdbcDaoRegister;
import io.edap.data.JdbcEntityDao;
import io.edap.data.JdbcViewDao;
import io.edap.data.jdbc.test.entity.Demo;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SingleStatementSessionTest extends AbstractDaoTest {

    @Test
    public void testDataSourceConnect() {
        Connection con = null;
        HikariConfig config = new HikariConfig();
        config.setDataSourceClassName("org.h2.jdbcx.JdbcDataSource");
        config.setConnectionTestQuery("VALUES 1");
        config.addDataSourceProperty("URL", "jdbc:h2:~/test");
        config.addDataSourceProperty("user", "sa");
        config.addDataSourceProperty("password", "");
        HikariDataSource ds = new HikariDataSource(config);
        JdbcEntityDao<Demo> allDao = JdbcDaoRegister.instance().getEntityDao(Demo.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setDataSource(ds);

            Demo demo = new Demo();
            long creatTime = System.currentTimeMillis();
            Thread.sleep(new Random().nextInt(10) + 1);
            long localDateTime = System.currentTimeMillis();
            demo.setCreateTime(creatTime);
            demo.setLocalDateTime(localDateTime);
            allDao.insert(demo);

            ResultSet rs = con.createStatement().executeQuery("select * from demo where id=1");
            int rows = 0;
            if (rs.next()) {
                assertEquals(rs.getLong("id"), 1);
                assertEquals(rs.getLong("create_time"), creatTime);
                assertEquals(rs.getLong("local_date_time"), localDateTime);
                rows++;
            }
            assertEquals(rows, 1);

        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    @Test
    public void testViewDataSourceConnect() {
        Connection con = null;
        HikariConfig config = new HikariConfig();
        config.setDataSourceClassName("org.h2.jdbcx.JdbcDataSource");
        config.setConnectionTestQuery("VALUES 1");
        config.addDataSourceProperty("URL", "jdbc:h2:~/test");
        config.addDataSourceProperty("user", "sa");
        config.addDataSourceProperty("password", "");
        HikariDataSource ds = new HikariDataSource(config);
        JdbcEntityDao<Demo> allDao = JdbcDaoRegister.instance().getEntityDao(Demo.class, new DaoOption());
        JdbcViewDao<Demo> allViewDao = JdbcDaoRegister.instance().getViewDao(Demo.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setDataSource(ds);

            Demo demo = new Demo();
            long creatTime = System.currentTimeMillis();
            Thread.sleep(new Random().nextInt(10) + 1);
            long localDateTime = System.currentTimeMillis();
            demo.setCreateTime(creatTime);
            demo.setLocalDateTime(localDateTime);
            allDao.insert(demo);

            ResultSet rs = con.createStatement().executeQuery("select * from demo where id=1");
            int rows = 0;
            if (rs.next()) {
                assertEquals(rs.getLong("id"), 1);
                assertEquals(rs.getLong("create_time"), creatTime);
                assertEquals(rs.getLong("local_date_time"), localDateTime);
                rows++;
            }
            assertEquals(rows, 1);


            allViewDao.setDataSource(ds);
            List<Demo> demos = allViewDao.query(" where id=1");
            assertNotNull(demos);
            assertEquals(demos.size(), 1);
            Demo qdemo = demos.get(0);
            assertEquals(qdemo.getId(), 1);
            assertEquals(qdemo.getCreateTime(), creatTime);
            assertEquals(qdemo.getLocalDateTime(), localDateTime);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    private void dropTable(Connection con) throws SQLException {
        con.createStatement().execute("drop table IF EXISTS demo");
    }

    private void createTable(Connection con) throws SQLException {
        String sql = "create table demo  (" +
                "id int primary key not null AUTO_INCREMENT, \n" +
                "create_time bigint,\n" +
                "local_date_time bigint" +
                ")";
        con.createStatement().execute(sql);
    }
}
