package io.edap.data.jdbc.test;

import io.edap.data.*;
import io.edap.data.jdbc.test.entity.DemoAllType;
import io.edap.data.model.ColumnsInfo;
import io.edap.data.util.DaoUtil;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AllTypeEntityTest extends AbstractDaoTest {

    @Test
    public void testInsert() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance().getEntityDao(DemoAllType.class, new DaoOption());
        try {
            ((JdbcBaseDao)allDao).getStatementSession().setDataSource(null);
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
                assertEquals(demo.getFieldLong(), rs.getLong("field_long"));
                assertEquals(demo.getFieldLongObj(), rs.getLong("field_long_obj"));
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
    public void testListInsert() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance().getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            List<DemoAllType> demos = new ArrayList<>();
            for (int i =1;i<=5;i++) {
                DemoAllType demo = buildDemoAllType(i+3);
                demos.add(demo);
            }
            int[] resRows = allDao.insert(demos);

            ResultSet rs = con.createStatement().executeQuery("select * from demo_all_type order by id");
            int rows = 0;
            while (rs.next()) {
                DemoAllType demo = demos.get(rows);
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
                assertEquals(demo.getFieldLong(), rs.getLong("field_long"));
                assertEquals(demo.getFieldLongObj(), rs.getLong("field_long_obj"));
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
            assertEquals(rows, 5);


            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            demos = new ArrayList<>();
            for (int i =1;i<=5;i++) {
                DemoAllType demo = buildDemoAllType(i+3);
                demo.setId(0);
                demos.add(demo);
            }
            resRows = allDao.insert(demos);

            rs = con.createStatement().executeQuery("select * from demo_all_type order by id");
            rows = 0;
            while (rs.next()) {
                DemoAllType demo = demos.get(rows);
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
                assertEquals(demo.getFieldLong(), rs.getLong("field_long"));
                assertEquals(demo.getFieldLongObj(), rs.getLong("field_long_obj"));
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
            StringBuilder allFields = new StringBuilder();
            ColumnsInfo columnsInfo = DaoUtil.getColumns(DemoAllType.class, new DaoOption());
            for (String col : columnsInfo.getColumns() ) {
                if (allFields.length() > 0) {
                    allFields.append(',');
                }
                allFields.append(col);
            }
            qdemos = allDao.query("select " + allFields + " from demo_all_type where id=1");
            assertEquals(qdemos.size(), 1);
            isAllEquals(demo, qdemos.get(0));

            qdemos = allDao.query(" where id=1");
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

    @Test
    public void testQueryObjectParam() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            List<DemoAllType> qdemos = allDao.query(
                    "select * from demo_all_type where id=?", 1L);
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

    @Test
    public void testQueryQueryParam() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            List<DemoAllType> qdemos = allDao.query(
                    "select * from demo_all_type where id=?", new QueryParam() {
                        @Override
                        public Object getParam() {
                            return 1L;
                        }
                    });
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

    @Test
    public void testFindOneNoParam() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            DemoAllType qdemo = allDao.findOne(
                    "select * from demo_all_type where id=1");
            assertNotNull(qdemo);
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
    public void testFindObjectParams() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            DemoAllType qdemo = allDao.findOne(
                    "select * from demo_all_type where id=?", 1L);
            assertNotNull(qdemo);
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
    public void testFindOneQueryParams() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            DemoAllType qdemo = allDao.findOne(
                    "select * from demo_all_type where id=?", () -> 1L);
            assertNotNull(qdemo);
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
    public void testUpdateById() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            DemoAllType qdemo = allDao.findOne(
                    "select * from demo_all_type where id=?", 1L);
            assertNotNull(qdemo);
            isAllEquals(demo, qdemo);
            DemoAllType updatedemo = buildUpdateDemoAllType(demo);
            allDao.updateById(updatedemo);
            qdemo = allDao.findOne(
                    "select * from demo_all_type where id=?", 1L);
            assertNotNull(qdemo);
            isAllEquals(updatedemo, qdemo);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    @Test
    public void testUpdateNoParam() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            DemoAllType qdemo = allDao.findOne(
                    "select * from demo_all_type where id=?", 1L);
            assertNotNull(qdemo);
            isAllEquals(demo, qdemo);
            allDao.update("update demo_all_type set field_big_decimal=31.41 where id=1");
            qdemo = allDao.findOne(
                    "select * from demo_all_type where id=?", 1L);
            assertNotNull(qdemo);
            assertFalse(qdemo.getFieldBigDecimal().equals(new BigDecimal("31.45")));
            assertEquals(qdemo.getFieldBigDecimal().doubleValue(), new BigDecimal(31.41).doubleValue());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }

        Exception thrown = assertThrows(Exception.class,
                () -> {
                    Connection con2;
                    con2 = openConnection();
                    dropTable(con2);
                    createTable(con2);
                    allDao.setConnection(con2);

                    DemoAllType demo = buildDemoAllType(1);
                    allDao.insert(demo);

                    allDao.update("update demo_all_type set field_big_decimal=31.47 where id2=1");
                });
        assertTrue(thrown.getMessage().contains("Column \"ID2\" not found"));
    }

    @Test
    public void testUpdateObjectParams() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            DemoAllType qdemo = allDao.findOne(
                    "select * from demo_all_type where id=?", 1L);
            assertNotNull(qdemo);
            isAllEquals(demo, qdemo);
            allDao.update("update demo_all_type set field_big_decimal=? where id=?",
                    new BigDecimal(31.46), 1L);
            qdemo = allDao.findOne(
                    "select * from demo_all_type where id=?", 1L);
            assertNotNull(qdemo);
            assertFalse(qdemo.getFieldBigDecimal().equals(new BigDecimal("31.41")));
            assertEquals(qdemo.getFieldBigDecimal().doubleValue(), new BigDecimal(31.46).doubleValue());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }

        Exception thrown = assertThrows(Exception.class,
                () -> {
                    Connection con2;
                    con2 = openConnection();
                    dropTable(con2);
                    createTable(con2);
                    allDao.setConnection(con2);

                    DemoAllType demo = buildDemoAllType(1);
                    allDao.insert(demo);

                    allDao.update("update demo_all_type set field_big_decimal=? where id2=?",
                            new BigDecimal(31.47), 1L);
                });
        assertTrue(thrown.getMessage().contains("Column \"ID2\" not found"));
    }

    @Test
    public void testUpdateQueryParams() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            DemoAllType qdemo = allDao.findOne(
                    "select * from demo_all_type where id=?", 1L);
            assertNotNull(qdemo);
            isAllEquals(demo, qdemo);
            allDao.update("update demo_all_type set field_big_decimal=? where id=?",
                    () -> new BigDecimal(31.47), () -> 1L);
            qdemo = allDao.findOne(
                    "select * from demo_all_type where id=?", 1L);
            assertNotNull(qdemo);
            assertFalse(qdemo.getFieldBigDecimal().equals(new BigDecimal("31.41")));
            assertEquals(qdemo.getFieldBigDecimal().doubleValue(), new BigDecimal(31.47).doubleValue());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }

        Exception thrown = assertThrows(Exception.class,
                () -> {
                    Connection con2;
                    con2 = openConnection();
                    dropTable(con2);
                    createTable(con2);
                    allDao.setConnection(con2);

                    DemoAllType demo = buildDemoAllType(1);
                    allDao.insert(demo);

                    allDao.update("update demo_all_type set field_big_decimal=? where id2=?",
                            () -> new BigDecimal(31.47), () -> 1L);
                });
        assertTrue(thrown.getMessage().contains("Column \"ID2\" not found"));
    }

    @Test
    public void testDeleteNoParam() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            int row = allDao.delete("delete from demo_all_type where id=1");
            assertEquals(row, 1);

            ResultSet rs = con.createStatement().executeQuery("select * from demo_all_type");
            boolean hasData = false;
            if (rs.next()) {
                hasData = true;
            }
            assertFalse(hasData);


            allDao.insert(demo);

            row = allDao.delete("where id=1");
            assertEquals(row, 1);
            rs = con.createStatement().executeQuery("select * from demo_all_type");
            hasData = false;
            if (rs.next()) {
                hasData = true;
            }
            assertFalse(hasData);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }

        Exception thrown = assertThrows(Exception.class,
                () -> {
                    Connection con2;
                    con2 = openConnection();
                    dropTable(con2);
                    createTable(con2);
                    allDao.setConnection(con2);

                    DemoAllType demo = buildDemoAllType(1);
                    allDao.insert(demo);

                    allDao.delete(" where id2=1");
                });
        assertTrue(thrown.getMessage().contains("Column \"ID2\" not found"));
    }

    @Test
    public void testDeleteObjectParams() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            int row = allDao.delete("delete from demo_all_type where id=?", 1L);
            assertEquals(row, 1);

            ResultSet rs = con.createStatement().executeQuery("select * from demo_all_type");
            boolean hasData = false;
            if (rs.next()) {
                hasData = true;
            }
            assertFalse(hasData);


            allDao.insert(demo);

            row = allDao.delete("where id=?", 1L);
            assertEquals(row, 1);
            rs = con.createStatement().executeQuery("select * from demo_all_type");
            hasData = false;
            if (rs.next()) {
                hasData = true;
            }
            assertFalse(hasData);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }

        Exception thrown = assertThrows(Exception.class,
                () -> {
                    Connection con2;
                    con2 = openConnection();
                    dropTable(con2);
                    createTable(con2);
                    allDao.setConnection(con2);

                    DemoAllType demo = buildDemoAllType(1);
                    allDao.insert(demo);

                    allDao.delete(" where id2=?", 1L);
                });
        assertTrue(thrown.getMessage().contains("Column \"ID2\" not found"));
    }

    @Test
    public void testDeleteQueryParams() {
        Connection con = null;
        JdbcEntityDao<DemoAllType> allDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoAllType.class, new DaoOption());
        try {
            con = openConnection();
            dropTable(con);
            createTable(con);
            allDao.setConnection(con);

            DemoAllType demo = buildDemoAllType(1);
            allDao.insert(demo);

            int row = allDao.delete("delete from demo_all_type where id=?", () -> 1L);
            assertEquals(row, 1);

            ResultSet rs = con.createStatement().executeQuery("select * from demo_all_type");
            boolean hasData = false;
            if (rs.next()) {
                hasData = true;
            }
            assertFalse(hasData);


            allDao.insert(demo);

            row = allDao.delete("where id=?", () -> 1L);
            assertEquals(row, 1);
            rs = con.createStatement().executeQuery("select * from demo_all_type");
            hasData = false;
            if (rs.next()) {
                hasData = true;
            }
            assertFalse(hasData);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }

        Exception thrown = assertThrows(Exception.class,
                () -> {
                    Connection con2;
                    con2 = openConnection();
                    dropTable(con2);
                    createTable(con2);
                    allDao.setConnection(con2);

                    DemoAllType demo = buildDemoAllType(1);
                    allDao.insert(demo);

                    allDao.delete(" where id2=?", () -> 1L);
                });
        assertTrue(thrown.getMessage().contains("Column \"ID2\" not found"));
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

    private DemoAllType buildUpdateDemoAllType(DemoAllType demo) {
        DemoAllType d = new DemoAllType();
        d.setFieldByte((byte)(demo.getFieldByte()+1));
        d.setFieldByteObj((byte)(demo.getFieldByteObj()+1));
        d.setFieldBoolean(!demo.isFieldBoolean());
        d.setFieldBooleanObj(!demo.getFieldBooleanObj());
        d.setFieldChar((char)(demo.getFieldChar()+1));
        d.setFieldCharObj((char)(demo.getFieldCharObj()+1));
        d.setFieldShort((short) (demo.getFieldShort()+1));
        d.setFieldShortObj((short) (demo.getFieldShortObj()+1));
        d.setFieldInt(demo.getFieldInt() + 1);
        d.setFieldIntObj(demo.getFieldIntObj() + 1);
        d.setFieldFloat(demo.getFieldFloat()+1);
        d.setFieldFloatObj(demo.getFieldFloatObj()+1);
        d.setFieldLong(demo.getFieldLong() + 1);
        d.setFieldLongObj((long) (demo.getFieldLongObj() + 1));
        d.setFieldDouble(demo.getFieldDouble()+1);
        d.setFieldDoubleObj(demo.getFieldDoubleObj()+1);
        d.setFieldTime(LocalTime.now().plus(1, ChronoUnit.SECONDS));
        d.setFieldDate(LocalDate.now().plus(1, ChronoUnit.DAYS));
        d.setFieldDateTime(LocalDateTime.now().plus(1, ChronoUnit.SECONDS));
        d.setFieldStr(demo.getFieldStr() + 1);
        d.setFieldByteArray(String.valueOf(System.currentTimeMillis() + 1).getBytes());
        d.setFieldBigDecimal(demo.getFieldBigDecimal().add(new BigDecimal(1)));
        d.setId(demo.getId());
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
