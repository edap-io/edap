package io.edap.data.jdbc.jdbc.test;

import io.edap.data.jdbc.DaoOption;
import io.edap.data.jdbc.JdbcDaoRegister;
import io.edap.data.jdbc.JdbcEntityDao;
import io.edap.data.jdbc.jdbc.test.entity.DemoLongObjId;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DemoLongObjIdEntityTest extends AbstractDaoTest {

    @Test
    public void testNoIdInsert() {
        Connection con = null;
        JdbcEntityDao<DemoLongObjId> allDao = JdbcDaoRegister.instance().getEntityDao(DemoLongObjId.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoLongObjId demo = new DemoLongObjId();
            long creatTime = System.currentTimeMillis();
            Thread.sleep(new Random().nextInt(10) + 1);
            long localDateTime = System.currentTimeMillis();
            demo.setCreateTime(creatTime);
            demo.setLocalDateTime(localDateTime);
            allDao.insert(demo);

            ResultSet rs = con.createStatement().executeQuery("select * from demo_long_obj_id where id=1");
            int rows = 0;
            if (rs.next()) {
                assertEquals(rs.getLong("id"), 1L);
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
        JdbcEntityDao<DemoLongObjId> allDao = JdbcDaoRegister.instance().getEntityDao(DemoLongObjId.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            List<DemoLongObjId> demos = new ArrayList<>();
            List<Long> creatTimes = new ArrayList<>();
            List<Long> localDateTimes = new ArrayList<>();
            for (int i=1;i<=5;i++) {
                DemoLongObjId demo = new DemoLongObjId();
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

            con = openConnection();
            ResultSet rs = con.createStatement().executeQuery("select * from demo_long_obj_id order by id");
            int rows = 0;
            while (rs.next()) {
                assertEquals(rs.getLong("id"), rows+1);
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

    @Test
    public void testSetIdInsert() {
        Connection con = null;
        JdbcEntityDao<DemoLongObjId> allDao = JdbcDaoRegister.instance().getEntityDao(DemoLongObjId.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoLongObjId demo = new DemoLongObjId();
            long id = new Random().nextInt(10000);
            demo.setId(id);
            long creatTime = System.currentTimeMillis();
            Thread.sleep(new Random().nextInt(10) + 1);
            long localDateTime = System.currentTimeMillis();
            demo.setCreateTime(creatTime);
            demo.setLocalDateTime(localDateTime);
            allDao.insert(demo);

            ResultSet rs = con.createStatement().executeQuery("select * from demo_long_obj_id");
            int rows = 0;
            if (rs.next()) {
                assertEquals(rs.getLong("id"), id);
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
        JdbcEntityDao<DemoLongObjId> allDao = JdbcDaoRegister.instance().getEntityDao(DemoLongObjId.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            List<DemoLongObjId> demos = new ArrayList<>();
            List<Long> ids = new ArrayList<>();
            List<Long> creatTimes = new ArrayList<>();
            List<Long> localDateTimes = new ArrayList<>();
            for (int i=1;i<=5;i++) {
                DemoLongObjId demo = new DemoLongObjId();
                long id = new Random().nextInt(10000);
                demo.setId(id);
                long creatTime = System.currentTimeMillis();
                Thread.sleep(new Random().nextInt(10) + 1);
                long localDateTime = System.currentTimeMillis();
                demo.setCreateTime(creatTime);
                demo.setLocalDateTime(localDateTime);
                demos.add(demo);
            }
            Collections.sort(demos, (o1, o2) -> {
                if (o1.getId() > o2.getId()) {
                    return 1;
                } else if (o1.getId() < o2.getId()) {
                    return -1;
                } else {
                    return 0;
                }
            });
            for (DemoLongObjId demo : demos) {
                ids.add(demo.getId());
                creatTimes.add(demo.getCreateTime());
                localDateTimes.add(demo.getLocalDateTime());
            }
            allDao.insert(demos);

            ResultSet rs = con.createStatement().executeQuery("select * from demo_long_obj_id order by id");
            int rows = 0;
            while (rs.next()) {
                assertEquals(rs.getLong("id"), ids.get(rows));
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
        con.createStatement().execute("drop table IF EXISTS demo_long_obj_id");
    }

    private void createTable(Connection con) throws SQLException {
        String sql = "create table demo_long_obj_id  (" +
                "id bigint primary key not null AUTO_INCREMENT, \n" +
                "create_time bigint,\n" +
                "local_date_time bigint" +
                ")";
        con.createStatement().execute(sql);
    }

}
