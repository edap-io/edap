package io.edap.data.jdbc.test;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.edap.data.JdbcDaoRegister;
import io.edap.data.JdbcMapDao;
import io.edap.data.QueryParam;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.*;
import java.sql.Date;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class JdbcMapDaoTest extends AbstractDaoTest {

    static Object[][] mapData;

    static {
        mapData = new Object[2][11];
        mapData[0] = new Object[]{1L, "123", 2, 3.1f, 3.14D,
                new java.sql.Date(System.currentTimeMillis()),
                new java.sql.Timestamp(System.currentTimeMillis()),
                new java.sql.Time(System.currentTimeMillis()),
                true,
                new BigDecimal("3.1415926"), "currentTimeMillis".getBytes()};
        mapData[1] = new Object[]{2L, "234", 3, 4.1f, 4.14D,
                new java.sql.Date(System.currentTimeMillis()),
                new java.sql.Timestamp(System.currentTimeMillis()),
                new java.sql.Time(System.currentTimeMillis()),
                false,
                new BigDecimal("3.14159265"), "System.currentTimeMillis".getBytes()};
    }

    @Test
    public void testStmtSessionDataSource() {
        HikariConfig config = new HikariConfig();
        config.setDataSourceClassName(JdbcDataSource.class.getName());
        //config.setJdbcUrl("jdbc:h2:tcp://localhost:9092/~/test");
        config.addDataSourceProperty("URL", "jdbc:h2:~/test");
        config.setUsername("sa");
        config.setPassword("");
        HikariDataSource ds = new HikariDataSource(config);

        Connection con = null;
        try {
            con = ds.getConnection();

            JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
            mapDao.setDataSource(ds);

            createTable(con);
            insertData(con);

            List<Map<String, Object>> list = mapDao.getList("select * from map_data order by id");
            assertNotNull(list);
            assertEquals(list.size(), 2);

        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }

    }

    @Test
    public void testGetListNoParam() {

        JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
        Connection con = null;
        try {
            con = openConnection();
            mapDao.setConnection(con);
            createTable(con);
            List<Map<String, Object>> list = mapDao.getList("select * from map_data order by id");
            assertNotNull(list);
            assertEquals(list.size(), 0);

            con = openConnection();
            mapDao.setConnection(con);
            insertData(con);

            list = mapDao.getList("select * from map_data order by id");
            assertNotNull(list);
            assertEquals(list.size(), mapData.length);
            for (int i=0;i<list.size();i++) {
                Map<String, Object> data = list.get(i);
                Object[] original = mapData[i];
                assertEquals(data.get("id".toUpperCase(Locale.ENGLISH)), original[0]);
                assertEquals(data.get("str_column".toUpperCase(Locale.ENGLISH)), original[1]);
                assertEquals(data.get("int_column".toUpperCase(Locale.ENGLISH)), original[2]);
                assertEquals(data.get("float_column".toUpperCase(Locale.ENGLISH)), original[3]);
                assertEquals(data.get("double_column".toUpperCase(Locale.ENGLISH)), original[4]);
                assertTrue(((Date)data.get("date_column".toUpperCase(Locale.ENGLISH))).toLocalDate().equals(((java.sql.Date)original[5]).toLocalDate()));
                assertEquals(data.get("datetime_column".toUpperCase(Locale.ENGLISH)), original[6]);
                assertEquals(((Time)data.get("time_column".toUpperCase(Locale.ENGLISH))).toLocalTime(), ((Time)original[7]).toLocalTime());
                assertEquals(data.get("bool_column".toUpperCase(Locale.ENGLISH)), original[8]);
                assertEquals(((BigDecimal)data.get("bigdecimal_column".toUpperCase(Locale.ENGLISH))).doubleValue(), ((BigDecimal)original[9]).doubleValue());
                assertArrayEquals((byte[])data.get("bytearray_column".toUpperCase(Locale.ENGLISH)), (byte[])original[10]);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    @Test
    public void testGetListNoParamException() {
        Exception thrown = assertThrows(Exception.class,
                () -> {
                    JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
                    Connection con = null;
                    con = openConnection();
                    mapDao.setConnection(con);
                    createTable(con);

                    con = openConnection();
                    mapDao.setConnection(con);
                    insertData(con);

                    mapDao.getList("select * from map_data1 order by id");
                });
        assertTrue(thrown.getMessage().contains("Table \"MAP_DATA1\" not found"));
    }

    @Test
    public void testGetListHasParams() {
        JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
        Connection con = null;
        try {
            con = openConnection();
            mapDao.setConnection(con);
            createTable(con);

            con = openConnection();
            mapDao.setConnection(con);
            insertData(con);

            List<Map<String, Object>> list = mapDao.getList("select * from map_data " +
                    "where id=? order by id", 1L);
            assertNotNull(list);
            assertEquals(list.size(), 1);
            for (int i=0;i<1;i++) {
                Map<String, Object> data = list.get(i);
                Object[] original = mapData[i];
                assertEquals(data.get("id".toUpperCase(Locale.ENGLISH)), original[0]);
                assertEquals(data.get("str_column".toUpperCase(Locale.ENGLISH)), original[1]);
                assertEquals(data.get("int_column".toUpperCase(Locale.ENGLISH)), original[2]);
                assertEquals(data.get("float_column".toUpperCase(Locale.ENGLISH)), original[3]);
                assertEquals(data.get("double_column".toUpperCase(Locale.ENGLISH)), original[4]);
                assertTrue(((Date)data.get("date_column".toUpperCase(Locale.ENGLISH))).toLocalDate().equals(((java.sql.Date)original[5]).toLocalDate()));
                assertEquals(data.get("datetime_column".toUpperCase(Locale.ENGLISH)), original[6]);
                assertEquals(((Time)data.get("time_column".toUpperCase(Locale.ENGLISH))).toLocalTime(), ((Time)original[7]).toLocalTime());
                assertEquals(data.get("bool_column".toUpperCase(Locale.ENGLISH)), original[8]);
                assertEquals(((BigDecimal)data.get("bigdecimal_column".toUpperCase(Locale.ENGLISH))).doubleValue(), ((BigDecimal)original[9]).doubleValue());
                assertArrayEquals((byte[])data.get("bytearray_column".toUpperCase(Locale.ENGLISH)), (byte[])original[10]);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    @Test
    public void testGetMapHasParams() {
        JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
        Connection con = null;
        try {
            con = openConnection();
            mapDao.setConnection(con);
            createTable(con);

            con = openConnection();
            mapDao.setConnection(con);
            insertData(con);

            Map<String, Object> data = mapDao.getMap("select * from map_data " +
                    "where id=? order by id", 1L);
            assertNotNull(data);
            assertEquals(data.size(), 11);
            Object[] original = mapData[0];
            assertEquals(data.get("id".toUpperCase(Locale.ENGLISH)), original[0]);
            assertEquals(data.get("str_column".toUpperCase(Locale.ENGLISH)), original[1]);
            assertEquals(data.get("int_column".toUpperCase(Locale.ENGLISH)), original[2]);
            assertEquals(data.get("float_column".toUpperCase(Locale.ENGLISH)), original[3]);
            assertEquals(data.get("double_column".toUpperCase(Locale.ENGLISH)), original[4]);
            assertEquals(((Date) data.get("date_column".toUpperCase(Locale.ENGLISH))).toLocalDate(), ((Date) original[5]).toLocalDate());
            assertEquals(data.get("datetime_column".toUpperCase(Locale.ENGLISH)), original[6]);
            assertEquals(((Time)data.get("time_column".toUpperCase(Locale.ENGLISH))).toLocalTime(), ((Time)original[7]).toLocalTime());
            assertEquals(data.get("bool_column".toUpperCase(Locale.ENGLISH)), original[8]);
            assertEquals(((BigDecimal)data.get("bigdecimal_column".toUpperCase(Locale.ENGLISH))).doubleValue(), ((BigDecimal)original[9]).doubleValue());
            assertArrayEquals((byte[])data.get("bytearray_column".toUpperCase(Locale.ENGLISH)), (byte[])original[10]);

            con = openConnection();
            mapDao.setConnection(con);
            data = mapDao.getMap("select * from map_data " +
                    "where id=? order by id", 5L);
            assertNotNull(data);
            assertEquals(data.size(), 0);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    @Test
    public void testGetMapHasParamObjects() {
        JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
        Connection con = null;
        try {
            con = openConnection();
            mapDao.setConnection(con);
            createTable(con);

            con = openConnection();
            mapDao.setConnection(con);
            insertData(con);

            Map<String, Object> data = mapDao.getMap("select * from map_data " +
                    "where id=? order by id", new QueryParam() {
                @Override
                public Object getParam() {
                    return 1L;
                }
            });
            assertNotNull(data);
            assertEquals(data.size(), 11);
            Object[] original = mapData[0];
            assertEquals(data.get("id".toUpperCase(Locale.ENGLISH)), original[0]);
            assertEquals(data.get("str_column".toUpperCase(Locale.ENGLISH)), original[1]);
            assertEquals(data.get("int_column".toUpperCase(Locale.ENGLISH)), original[2]);
            assertEquals(data.get("float_column".toUpperCase(Locale.ENGLISH)), original[3]);
            assertEquals(data.get("double_column".toUpperCase(Locale.ENGLISH)), original[4]);
            assertTrue(((Date)data.get("date_column".toUpperCase(Locale.ENGLISH))).toLocalDate().equals(((java.sql.Date)original[5]).toLocalDate()));
            assertEquals(data.get("datetime_column".toUpperCase(Locale.ENGLISH)), original[6]);
            assertEquals(((Time)data.get("time_column".toUpperCase(Locale.ENGLISH))).toLocalTime(), ((Time)original[7]).toLocalTime());
            assertEquals(data.get("bool_column".toUpperCase(Locale.ENGLISH)), original[8]);
            assertEquals(((BigDecimal)data.get("bigdecimal_column".toUpperCase(Locale.ENGLISH))).doubleValue(), ((BigDecimal)original[9]).doubleValue());
            assertArrayEquals((byte[])data.get("bytearray_column".toUpperCase(Locale.ENGLISH)), (byte[])original[10]);

            con = openConnection();
            mapDao.setConnection(con);

            data = mapDao.getMap("select * from map_data " +
                    "where id=? order by id", new QueryParam() {
                @Override
                public Object getParam() {
                    return 5L;
                }
            });
            assertNotNull(data);
            assertEquals(data.size(), 0);

        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    @Test
    public void testGetMapNoParams() {
        JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
        Connection con = null;
        try {
            con = openConnection();
            mapDao.setConnection(con);
            createTable(con);

            con = openConnection();
            mapDao.setConnection(con);
            insertData(con);

            Map<String, Object> data = mapDao.getMap("select * from map_data " +
                    "where id=1 order by id");
            assertNotNull(data);
            assertEquals(data.size(), 11);
            Object[] original = mapData[0];
            assertEquals(data.get("id".toUpperCase(Locale.ENGLISH)), original[0]);
            assertEquals(data.get("str_column".toUpperCase(Locale.ENGLISH)), original[1]);
            assertEquals(data.get("int_column".toUpperCase(Locale.ENGLISH)), original[2]);
            assertEquals(data.get("float_column".toUpperCase(Locale.ENGLISH)), original[3]);
            assertEquals(data.get("double_column".toUpperCase(Locale.ENGLISH)), original[4]);
            assertTrue(((Date)data.get("date_column".toUpperCase(Locale.ENGLISH))).toLocalDate().equals(((java.sql.Date)original[5]).toLocalDate()));
            assertEquals(data.get("datetime_column".toUpperCase(Locale.ENGLISH)), original[6]);
            assertEquals(((Time)data.get("time_column".toUpperCase(Locale.ENGLISH))).toLocalTime(), ((Time)original[7]).toLocalTime());
            assertEquals(data.get("bool_column".toUpperCase(Locale.ENGLISH)), original[8]);
            assertEquals(((BigDecimal)data.get("bigdecimal_column".toUpperCase(Locale.ENGLISH))).doubleValue(), ((BigDecimal)original[9]).doubleValue());
            assertArrayEquals((byte[])data.get("bytearray_column".toUpperCase(Locale.ENGLISH)), (byte[])original[10]);

            con = openConnection();
            mapDao.setConnection(con);
            data = mapDao.getMap("select * from map_data " +
                    "where id=5 order by id");
            assertNotNull(data);
            assertEquals(data.size(), 0);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    @Test
    public void testGetListHasParamObjects() {
        JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
        Connection con = null;
        try {
            con = openConnection();
            mapDao.setConnection(con);
            createTable(con);

            con = openConnection();
            mapDao.setConnection(con);
            insertData(con);

            List<Map<String, Object>> list = mapDao.getList("select * from map_data " +
                    "where id=? order by id", new QueryParam() {
                @Override
                public Object getParam() {
                    return 1L;
                }
            });
            assertNotNull(list);
            assertEquals(list.size(), 1);
            for (int i=0;i<1;i++) {
                Map<String, Object> data = list.get(i);
                Object[] original = mapData[i];
                assertEquals(data.get("id".toUpperCase(Locale.ENGLISH)), original[0]);
                assertEquals(data.get("str_column".toUpperCase(Locale.ENGLISH)), original[1]);
                assertEquals(data.get("int_column".toUpperCase(Locale.ENGLISH)), original[2]);
                assertEquals(data.get("float_column".toUpperCase(Locale.ENGLISH)), original[3]);
                assertEquals(data.get("double_column".toUpperCase(Locale.ENGLISH)), original[4]);
                assertTrue(((Date)data.get("date_column".toUpperCase(Locale.ENGLISH))).toLocalDate().equals(((java.sql.Date)original[5]).toLocalDate()));
                assertEquals(data.get("datetime_column".toUpperCase(Locale.ENGLISH)), original[6]);
                assertEquals(((Time)data.get("time_column".toUpperCase(Locale.ENGLISH))).toLocalTime(), ((Time)original[7]).toLocalTime());
                assertEquals(data.get("bool_column".toUpperCase(Locale.ENGLISH)), original[8]);
                assertEquals(((BigDecimal)data.get("bigdecimal_column".toUpperCase(Locale.ENGLISH))).doubleValue(), ((BigDecimal)original[9]).doubleValue());
                assertArrayEquals((byte[])data.get("bytearray_column".toUpperCase(Locale.ENGLISH)), (byte[])original[10]);
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    @Test
    public void testGetListHasParamsException() {
        Exception thrown = assertThrows(Exception.class,
                () -> {
                    JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
                    Connection con = null;
                    con = openConnection();
                    mapDao.setConnection(con);
                    createTable(con);

                    con = openConnection();
                    mapDao.setConnection(con);
                    insertData(con);

                    mapDao.getList("select * from map_data1 where id=? order by id", 1L);
                });
        assertTrue(thrown.getMessage().contains("Table \"MAP_DATA1\" not found"));

    }

    @Test
    public void testGetMapHasParamsException() {
        Exception thrown = assertThrows(Exception.class,
                () -> {
                    JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
                    Connection con = null;
                    con = openConnection();
                    mapDao.setConnection(con);
                    createTable(con);

                    con = openConnection();
                    mapDao.setConnection(con);
                    insertData(con);

                    mapDao.getMap("select * from map_data1 where id=? order by id", 1L);
                });
        assertTrue(thrown.getMessage().contains("Table \"MAP_DATA1\" not found"));
    }

    @Test
    public void testGetListHasParamObjectsException() {
        Exception thrown = assertThrows(Exception.class,
                () -> {
                    JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
                    Connection con = null;
                    con = openConnection();
                    mapDao.setConnection(con);
                    createTable(con);

                    con = openConnection();
                    mapDao.setConnection(con);
                    insertData(con);

                    mapDao.getList("select * from map_data1 where id=? order by id", new QueryParam() {
                        @Override
                        public Object getParam() {
                            return 1L;
                        }
                    });
                });
        assertTrue(thrown.getMessage().contains("Table \"MAP_DATA1\" not found"));
    }

    @Test
    public void testGetMapHasParamObjectsException() {
        Exception thrown = assertThrows(Exception.class,
                () -> {
                    JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
                    Connection con = null;
                    con = openConnection();
                    mapDao.setConnection(con);
                    createTable(con);

                    con = openConnection();
                    mapDao.setConnection(con);
                    insertData(con);

                    mapDao.getMap("select * from map_data1 where id=? order by id", new QueryParam() {
                        @Override
                        public Object getParam() {
                            return 1L;
                        }
                    });
                });
        assertTrue(thrown.getMessage().contains("Table \"MAP_DATA1\" not found"));
    }

    @Test
    public void testUpdateNoParam() {
        JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
        Connection con = null;
        try {
            con = openConnection();
            mapDao.setConnection(con);
            createTable(con);

            con = openConnection();
            mapDao.setConnection(con);
            insertData(con);

            con = openConnection();
            mapDao.setConnection(con);
            int row = mapDao.update("update map_data set int_column=100");
            assertEquals(row, 2);

            con = openConnection();
            mapDao.setConnection(con);
            List<Map<String, Object>> list = mapDao.getList("select * from map_data " +
                    "where id=? order by id", new QueryParam() {
                @Override
                public Object getParam() {
                    return 1L;
                }
            });

            assertNotNull(list);
            assertEquals(list.size(), 1);
            for (int i=0;i<1;i++) {
                Map<String, Object> data = list.get(i);
                Object[] original = mapData[i];
                assertEquals(data.get("id".toUpperCase(Locale.ENGLISH)), original[0]);
                assertEquals(data.get("str_column".toUpperCase(Locale.ENGLISH)), original[1]);
                assertEquals(data.get("int_column".toUpperCase(Locale.ENGLISH)), 100);
                assertEquals(data.get("float_column".toUpperCase(Locale.ENGLISH)), original[3]);
                assertEquals(data.get("double_column".toUpperCase(Locale.ENGLISH)), original[4]);
                assertTrue(((Date)data.get("date_column".toUpperCase(Locale.ENGLISH))).toLocalDate().equals(((java.sql.Date)original[5]).toLocalDate()));
                assertEquals(data.get("datetime_column".toUpperCase(Locale.ENGLISH)), original[6]);
                assertEquals(((Time)data.get("time_column".toUpperCase(Locale.ENGLISH))).toLocalTime(), ((Time)original[7]).toLocalTime());
                assertEquals(data.get("bool_column".toUpperCase(Locale.ENGLISH)), original[8]);
                assertEquals(((BigDecimal)data.get("bigdecimal_column".toUpperCase(Locale.ENGLISH))).doubleValue(), ((BigDecimal)original[9]).doubleValue());
                assertArrayEquals((byte[])data.get("bytearray_column".toUpperCase(Locale.ENGLISH)), (byte[])original[10]);
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    @Test
    public void testUpdateHasParams() {
        JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
        Connection con = null;
        try {
            con = openConnection();
            mapDao.setConnection(con);
            createTable(con);

            con = openConnection();
            mapDao.setConnection(con);
            insertData(con);

            con = openConnection();
            mapDao.setConnection(con);
            int row = mapDao.update("update map_data set int_column=100 where id>?", 0L);
            assertEquals(row, 2);

            con = openConnection();
            mapDao.setConnection(con);
            List<Map<String, Object>> list = mapDao.getList("select * from map_data " +
                    "where id>? order by id", new QueryParam() {
                @Override
                public Object getParam() {
                    return 0L;
                }
            });

            assertNotNull(list);
            assertEquals(list.size(), 2);
            for (int i=0;i<2;i++) {
                Map<String, Object> data = list.get(i);
                Object[] original = mapData[i];
                assertEquals(data.get("id".toUpperCase(Locale.ENGLISH)), original[0]);
                assertEquals(data.get("str_column".toUpperCase(Locale.ENGLISH)), original[1]);
                assertEquals(data.get("int_column".toUpperCase(Locale.ENGLISH)), 100);
                assertEquals(data.get("float_column".toUpperCase(Locale.ENGLISH)), original[3]);
                assertEquals(data.get("double_column".toUpperCase(Locale.ENGLISH)), original[4]);
                assertTrue(((Date)data.get("date_column".toUpperCase(Locale.ENGLISH))).toLocalDate().equals(((java.sql.Date)original[5]).toLocalDate()));
                assertEquals(data.get("datetime_column".toUpperCase(Locale.ENGLISH)), original[6]);
                assertEquals(((Time)data.get("time_column".toUpperCase(Locale.ENGLISH))).toLocalTime(), ((Time)original[7]).toLocalTime());
                assertEquals(data.get("bool_column".toUpperCase(Locale.ENGLISH)), original[8]);
                assertEquals(((BigDecimal)data.get("bigdecimal_column".toUpperCase(Locale.ENGLISH))).doubleValue(), ((BigDecimal)original[9]).doubleValue());
                assertArrayEquals((byte[])data.get("bytearray_column".toUpperCase(Locale.ENGLISH)), (byte[])original[10]);
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    @Test
    public void testUpdateHasParamObjects() {
        JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
        Connection con = null;
        try {
            con = openConnection();
            mapDao.setConnection(con);
            createTable(con);

            con = openConnection();
            mapDao.setConnection(con);
            insertData(con);

            con = openConnection();
            mapDao.setConnection(con);
            int row = mapDao.update("update map_data set int_column=100 where id>?", new QueryParam() {
                @Override
                public Object getParam() {
                    return 0L;
                }
            });
            assertEquals(row, 2);

            con = openConnection();
            mapDao.setConnection(con);
            List<Map<String, Object>> list = mapDao.getList("select * from map_data " +
                    "where id>? order by id", new QueryParam() {
                @Override
                public Object getParam() {
                    return 0L;
                }
            });

            assertNotNull(list);
            assertEquals(list.size(), 2);
            for (int i=0;i<2;i++) {
                Map<String, Object> data = list.get(i);
                Object[] original = mapData[i];
                assertEquals(data.get("id".toUpperCase(Locale.ENGLISH)), original[0]);
                assertEquals(data.get("str_column".toUpperCase(Locale.ENGLISH)), original[1]);
                assertEquals(data.get("int_column".toUpperCase(Locale.ENGLISH)), 100);
                assertEquals(data.get("float_column".toUpperCase(Locale.ENGLISH)), original[3]);
                assertEquals(data.get("double_column".toUpperCase(Locale.ENGLISH)), original[4]);
                assertTrue(((Date)data.get("date_column".toUpperCase(Locale.ENGLISH))).toLocalDate().equals(((java.sql.Date)original[5]).toLocalDate()));
                assertEquals(data.get("datetime_column".toUpperCase(Locale.ENGLISH)), original[6]);
                assertEquals(((Time)data.get("time_column".toUpperCase(Locale.ENGLISH))).toLocalTime(), ((Time)original[7]).toLocalTime());
                assertEquals(data.get("bool_column".toUpperCase(Locale.ENGLISH)), original[8]);
                assertEquals(((BigDecimal)data.get("bigdecimal_column".toUpperCase(Locale.ENGLISH))).doubleValue(), ((BigDecimal)original[9]).doubleValue());
                assertArrayEquals((byte[])data.get("bytearray_column".toUpperCase(Locale.ENGLISH)), (byte[])original[10]);
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }

    @Test
    public void testUpdateNoParamException() {
        Exception thrown = assertThrows(Exception.class,
                () -> {
                    JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
                    Connection con = null;
                    con = openConnection();
                    mapDao.setConnection(con);
                    createTable(con);

                    con = openConnection();
                    mapDao.setConnection(con);
                    insertData(con);

                    mapDao.update("update map_data1 set int_column=100");
                });
        assertTrue(thrown.getMessage().contains("Table \"MAP_DATA1\" not found"));
    }

    @Test
    public void testUpdateHasParamsException() {
        Exception thrown = assertThrows(Exception.class,
                () -> {
                    JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
                    Connection con = null;
                    con = openConnection();
                    mapDao.setConnection(con);
                    createTable(con);

                    con = openConnection();
                    mapDao.setConnection(con);
                    insertData(con);

                    mapDao.update("update map_data1 set int_column=100 where id>?", 0L);
                });
        assertTrue(thrown.getMessage().contains("Table \"MAP_DATA1\" not found"));
    }

    @Test
    public void testUpdateHasParamObjectsException() {
        Exception thrown = assertThrows(Exception.class,
                () -> {
                    JdbcMapDao mapDao = JdbcDaoRegister.instance().getMapDao();
                    Connection con = null;
                    con = openConnection();
                    mapDao.setConnection(con);
                    createTable(con);

                    con = openConnection();
                    mapDao.setConnection(con);
                    insertData(con);

                    mapDao.update("update map_data1 set int_column=100 where id>?", new QueryParam() {
                        @Override
                        public Object getParam() {
                            return 0L;
                        }
                    });
                });
        assertTrue(thrown.getMessage().contains("Table \"MAP_DATA1\" not found"));
    }

    private void createTable(Connection con) {
        String createSql = "create table map_data \n" +
                "( \n" +
                "id bigint primary key not null AUTO_INCREMENT, \n" +
                "str_column varchar(200) null, \n" +
                "int_column int," +
                "float_column REAL," +
                "double_column DOUBLE PRECISION," +
                "date_column DATE," +
                "datetime_column TIMESTAMP," +
                "time_column TIME(9)," +
                "bool_column boolean," +
                "bigdecimal_column NUMERIC(20,8)," +
                "bytearray_column VARBINARY(1000)" +
                ")";
        try {
            System.out.println("connect success");
            con.createStatement().execute("drop table IF EXISTS map_data");
            con.createStatement().execute(createSql);

        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void insertData(Connection con) {
        try {

            PreparedStatement pstmt = con.prepareStatement("insert into map_data " +
                    "(id, str_column, int_column, float_column, double_column," +
                    "date_column, datetime_column, time_column, bool_column, bigdecimal_column," +
                    "bytearray_column) values (?,?,?,?,?,?,?,?,?,?,?)");
            for (int i=0;i<2;i++) {
                Object[] data = mapData[i];
                pstmt.setLong(1, (long)data[0]);
                pstmt.setString(2, (String)data[1]);
                pstmt.setInt(3, (int)data[2]);
                pstmt.setFloat(4, (float)data[3]);
                pstmt.setDouble(5, (double)data[4]);
                pstmt.setDate(6, (java.sql.Date)data[5]);
                pstmt.setTimestamp(7, (java.sql.Timestamp)data[6]);
                pstmt.setTime(8, (java.sql.Time)data[7]);
                pstmt.setBoolean(9, (boolean)data[8]);
                pstmt.setBigDecimal(10, (BigDecimal) data[9]);
                pstmt.setBytes(11, (byte[])data[10]);
                pstmt.addBatch();
            }
            int[] rows = pstmt.executeBatch();
            System.out.println("rows:" + Arrays.toString(rows));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
