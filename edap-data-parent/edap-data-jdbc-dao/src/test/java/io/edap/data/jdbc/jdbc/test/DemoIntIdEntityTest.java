package io.edap.data.jdbc.jdbc.test;

import io.edap.data.jdbc.DaoOption;
import io.edap.data.jdbc.JdbcDaoRegister;
import io.edap.data.jdbc.JdbcEntityDao;
import io.edap.data.jdbc.jdbc.test.entity.DemoIntId;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DemoIntIdEntityTest extends AbstractDaoTest {

    @Test
    public void testNoIdInsert() {
        Connection con = null;
        JdbcEntityDao<DemoIntId> allDao = JdbcDaoRegister.instance().getEntityDao(DemoIntId.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoIntId demo = new DemoIntId();
            long creatTime = System.currentTimeMillis();
            Thread.sleep(new Random().nextInt(10) + 1);
            long localDateTime = System.currentTimeMillis();
            demo.setCreateTime(creatTime);
            demo.setLocalDateTime(localDateTime);
            allDao.insert(demo);

            ResultSet rs = con.createStatement().executeQuery("select * from demo_int_id where id=1");
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
    public void testNoIdListInsert() {
        Connection con = null;
        JdbcEntityDao<DemoIntId> allDao = JdbcDaoRegister.instance().getEntityDao(DemoIntId.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            List<DemoIntId> demos = new ArrayList<>();
            List<Long> createTimes = new ArrayList<>();
            List<Long> localDateTimes = new ArrayList<>();
            for (int i=1;i<=5;i++) {
                DemoIntId demo = new DemoIntId();
                long creatTime = System.currentTimeMillis();
                createTimes.add(creatTime);
                Thread.sleep(new Random().nextInt(10) + 1);
                long localDateTime = System.currentTimeMillis();
                demo.setCreateTime(creatTime);
                demo.setLocalDateTime(localDateTime);
                localDateTimes.add(localDateTime);
                demos.add(demo);
            }
            allDao.insert(demos);

            ResultSet rs = con.createStatement().executeQuery("select * from demo_int_id order by id");
            int rows = 0;
            while (rs.next()) {
                assertEquals(rs.getLong("id"), rows+1);
                assertEquals(rs.getLong("create_time"), createTimes.get(rows));
                assertEquals(rs.getLong("local_date_time"), localDateTimes.get(rows));
                rows++;
            }
            assertEquals(rows, 5);

        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    @Test
    public void testSetIdInsert() {
        Connection con = null;
        JdbcEntityDao<DemoIntId> allDao = JdbcDaoRegister.instance().getEntityDao(DemoIntId.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoIntId demo = new DemoIntId();
            int id = new Random().nextInt(10000);
            demo.setId(id);
            long creatTime = System.currentTimeMillis();
            Thread.sleep(new Random().nextInt(10) + 1);
            long localDateTime = System.currentTimeMillis();
            demo.setCreateTime(creatTime);
            demo.setLocalDateTime(localDateTime);
            allDao.insert(demo);

            ResultSet rs = con.createStatement().executeQuery("select * from demo_int_id");
            int rows = 0;
            if (rs.next()) {
                assertEquals(rs.getInt("id"), id);
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
    public void testSetIdListInsert() {
        Connection con = null;
        JdbcEntityDao<DemoIntId> allDao = JdbcDaoRegister.instance().getEntityDao(DemoIntId.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            List<DemoIntId> demos = new ArrayList<>();
            List<Integer> ids = new ArrayList<>();
            List<Long> creatTimes = new ArrayList<>();
            List<Long> localDateTimes = new ArrayList<>();
            for (int i=1;i<=5;i++) {
                DemoIntId demo = new DemoIntId();
                int id = i;
                ids.add(id);
                demo.setId(id);
                long creatTime = System.currentTimeMillis();
                creatTimes.add(creatTime);
                Thread.sleep(new Random().nextInt(10) + 1);
                long localDateTime = System.currentTimeMillis();
                localDateTimes.add(localDateTime);
                demo.setCreateTime(creatTime);
                demo.setLocalDateTime(localDateTime);
                demos.add(demo);
            }
            allDao.insert(demos);

            ResultSet rs = con.createStatement().executeQuery("select * from demo_int_id order by id");
            int rows = 0;
            while (rs.next()) {
                assertEquals(rs.getInt("id"), ids.get(rows));
                assertEquals(rs.getLong("create_time"), creatTimes.get(rows));
                assertEquals(rs.getLong("local_date_time"), localDateTimes.get(rows));
                rows++;
            }
            assertEquals(rows, 5);

        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    private void dropTable(Connection con) throws SQLException {
        con.createStatement().execute("drop table IF EXISTS demo_int_id");
    }

    private void createTable(Connection con) throws SQLException {
        String sql = "create table demo_int_id  (" +
                "id int primary key not null AUTO_INCREMENT, \n" +
                "create_time bigint,\n" +
                "local_date_time bigint" +
                ")";
        con.createStatement().execute(sql);
    }
}
