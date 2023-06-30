package io.edap.data.jdbc.test;

import io.edap.data.DaoOption;
import io.edap.data.JdbcDaoRegister;
import io.edap.data.JdbcEntityDao;
import io.edap.data.jdbc.test.entity.DemoAllType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AllTypeEntityTest extends AbstractDaoTest {

    @Test
    public void testInsert() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance().getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            ResultSet rs = con.createStatement().executeQuery("select * from demo_all_type where id=1");
            int rows = 0;
            if (rs.next()) {
                assertEquals(demo.getId(), rs.getLong("id"));
                assertEquals(demo.getFieldByte(), rs.getByte("field_byte"));
                assertEquals(demo.getFieldByteObj().byteValue(), rs.getByte("field_byte_obj"));
                assertEquals(demo.isFieldBoolean(), rs.getBoolean("field_byte"));
                assertEquals(demo.getFieldBooleanObj(), rs.getBoolean("field_boolean_obj"));
                assertEquals(demo.getFieldChar(), rs.getString("field_char").charAt(0));
                assertEquals(demo.getFieldCharObj(), rs.getString("field_char_obj").charAt(0));
                assertEquals(demo.getFieldShort(), rs.getShort("field_short"));
                assertEquals(demo.getFieldShortObj(), rs.getShort("field_short_obj"));
                assertEquals(demo.getFieldInt(), rs.getInt("field_int"));
                assertEquals(demo.getFieldIntObj(), rs.getInt("field_int_obj"));
                assertEquals(demo.getFieldLong(), rs.getInt("field_long"));
                assertEquals(demo.getFieldLongObj(), rs.getInt("field_long_obj"));
                assertEquals(demo.getFieldFloat(), rs.getFloat("field_float"));
                assertEquals(demo.getFieldFloatObj(), rs.getFloat("field_float_obj"));
                assertEquals(demo.getFieldDouble(), rs.getDouble("field_double"));
                assertEquals(demo.getFieldDoubleObj(), rs.getDouble("field_double_obj"));
                assertEquals(demo.getFieldTime().plus(-demo.getFieldTime().getNano(), ChronoUnit.NANOS), rs.getTime("field_time").toLocalTime());
                assertEquals(demo.getFieldDate(), rs.getDate("field_date").toLocalDate());
                assertEquals(demo.getFieldDateTime(), rs.getTimestamp("field_date_time").toLocalDateTime());
                assertArrayEquals(demo.getFieldByteArray(), rs.getBytes("field_byte_array"));
                assertEquals(demo.getFieldBigDecimal(), rs.getBigDecimal("field_big_decimal"));
                assertEquals(demo.getFieldStr(), rs.getString("field_str"));
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
    public void testFindById() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance().getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            DemoAllType qdemo = allDao.findById(1L);
            isAllEquals(demo, qdemo);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    @Test
    public void testQueryNoParam() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance().getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            List<DemoAllType> qdemos = allDao.query("select * from demo_all_type where id=1");
            assertEquals(qdemos.size(), 1);
            isAllEquals(demo, qdemos.get(0));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    private void isAllEquals(DemoAllType demo, DemoAllType qdemo) {
        assertEquals(demo.getId(), qdemo.getId());
        assertEquals(demo.getFieldByte(), qdemo.getFieldByte());
        assertEquals(demo.getFieldByteObj().byteValue(), qdemo.getFieldByteObj().byteValue());
        assertEquals(demo.isFieldBoolean(), qdemo.isFieldBoolean());
        assertEquals(demo.getFieldBooleanObj(), qdemo.getFieldBooleanObj());
        assertEquals(demo.getFieldChar(), qdemo.getFieldChar());
        assertEquals(demo.getFieldCharObj(), qdemo.getFieldCharObj());
        assertEquals(demo.getFieldShort(), qdemo.getFieldShort());
        assertEquals(demo.getFieldShortObj(), qdemo.getFieldShortObj());
        assertEquals(demo.getFieldInt(), qdemo.getFieldInt());
        assertEquals(demo.getFieldIntObj(), qdemo.getFieldIntObj());
        assertEquals(demo.getFieldLong(), qdemo.getFieldLong());
        assertEquals(demo.getFieldLongObj(), qdemo.getFieldLongObj());
        assertEquals(demo.getFieldFloat(), qdemo.getFieldFloat());
        assertEquals(demo.getFieldFloatObj(), qdemo.getFieldFloatObj());
        assertEquals(demo.getFieldDouble(), qdemo.getFieldDouble());
        assertEquals(demo.getFieldDoubleObj(), qdemo.getFieldDoubleObj());
        assertEquals(demo.getFieldTime().plus(-demo.getFieldTime().getNano(), ChronoUnit.NANOS), qdemo.getFieldTime());
        assertEquals(demo.getFieldDate(), qdemo.getFieldDate());
        assertEquals(demo.getFieldDateTime(), qdemo.getFieldDateTime());
        assertArrayEquals(demo.getFieldByteArray(), qdemo.getFieldByteArray());
        assertEquals(demo.getFieldBigDecimal(), qdemo.getFieldBigDecimal());
        assertEquals(demo.getFieldStr(), qdemo.getFieldStr());
    }

    private DemoAllType buildDemoAllType(int seq) {
        DemoAllType d = new DemoAllType();
        d.setFieldByte((byte)'a');
        d.setFieldByteObj((byte)'b');
        d.setFieldBoolean(true);
        d.setFieldBooleanObj(false);
        d.setFieldChar('c');
        d.setFieldCharObj('d');
        d.setFieldShort((short) 10);
        d.setFieldShortObj((short) 20);
        d.setFieldInt(100 + seq);
        d.setFieldIntObj(200 + seq);
        d.setFieldFloat(11.2f);
        d.setFieldFloatObj(12.3f);
        d.setFieldLong(1000 + seq);
        d.setFieldLongObj((long) (2000 + seq));
        d.setFieldDouble(123456.78);
        d.setFieldDoubleObj(234567.89);
        d.setFieldTime(LocalTime.now());
        d.setFieldDate(LocalDate.now());
        d.setFieldDateTime(LocalDateTime.now());
        d.setFieldStr("fieldStrValue" + seq);
        d.setFieldByteArray(String.valueOf(System.currentTimeMillis()).getBytes());
        d.setFieldBigDecimal(new BigDecimal("9876543.21"));
        d.setId(seq);
        return d;
    }

    private void dropTable(Connection con) throws SQLException {
        con.createStatement().execute("drop table IF EXISTS demo_all_type");
    }

    private void createTable(Connection con) throws SQLException {
        String sql = "create table demo_all_type  (" +
                "id bigint primary key not null AUTO_INCREMENT, \n" +
                "field_str varchar(200),\n" +
                "field_int INT,\n" +
                "field_int_obj INT,\n" +
                "field_long BIGINT,\n" +
                "field_long_obj BIGINT,\n" +
                "field_float REAL,\n" +
                "field_float_obj REAL,\n" +
                "field_double DOUBLE PRECISION,\n" +
                "field_double_obj DOUBLE PRECISION,\n" +
                "field_boolean BOOLEAN,\n" +
                "field_boolean_obj BOOLEAN,\n" +
                "field_date DATE,\n" +
                "field_time TIME(9),\n" +
                "field_date_time TIMESTAMP," +
                "field_byte TINYINT," +
                "field_byte_obj TINYINT," +
                "field_short SMALLINT," +
                "field_short_obj SMALLINT," +
                "field_char varchar(1)," +
                "field_char_obj varchar(1)," +
                "field_byte_array VARBINARY(1000)," +
                "field_big_decimal NUMERIC(20, 2)" +
                ")";
        con.createStatement().execute(sql);
    }
}
