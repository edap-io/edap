package io.edap.data.jdbc.test;

import io.edap.data.DaoOption;
import io.edap.data.JdbcDaoRegister;
import io.edap.data.JdbcEntityDao;
import io.edap.data.jdbc.test.entity.DemoLongObjId;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;

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
