//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package edao.io.edap.data.jdbc.test.entity;

import io.edap.data.JdbcBaseDao;
import io.edap.data.JdbcDaoRegister;
import io.edap.data.JdbcEntityDao;
import io.edap.data.JdbcFieldSetFunc;
import io.edap.data.QueryParam;
import io.edap.data.StatementSession;
import io.edap.data.jdbc.test.entity.DemoAllType;
import io.edap.data.util.Convertor;
import io.edap.util.CollectionUtils;
import io.edap.util.Constants;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DemoAllTypeJdbcEntityDao2 extends JdbcBaseDao implements JdbcEntityDao<DemoAllType> {
    static Map<String, JdbcFieldSetFunc<DemoAllType>> FIELD_SET_FUNCS = new ConcurrentHashMap();

    public DemoAllTypeJdbcEntityDao2() {
    }

    public int[] insert(List<DemoAllType> var1) throws Exception {
        if (CollectionUtils.isEmpty(var1)) {
            return null;
        } else {
            StatementSession var2 = this.getStatementSession();

            try {
                boolean hassedIdValue = this.hasIdValue(var1.get(0).getId());
                PreparedStatement var3;
                if (hassedIdValue) {
                    var3 = var2.prepareStatement("INSERT INTO demo_all_type (field_str,field_int,field_int_obj,field_long,field_long_obj,field_float,field_float_obj,field_double,field_double_obj,field_boolean,field_boolean_obj,field_date,field_time,field_date_time,field_byte,field_byte_obj,field_short,field_short_obj,field_char,field_char_obj,field_byte_array,field_big_decimal,id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
                } else {
                    var3 = var2.prepareStatement("INSERT INTO demo_all_type (field_str,field_int,field_int_obj,field_long,field_long_obj,field_float,field_float_obj,field_double,field_double_obj,field_boolean,field_boolean_obj,field_date,field_time,field_date_time,field_byte,field_byte_obj,field_short,field_short_obj,field_char,field_char_obj,field_byte_array,field_big_decimal) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
                }
                boolean var4 = var2.getAutoCommit();
                if (var4) {
                    var2.setAutoCommit(false);
                }

                var3.clearBatch();
                int var5 = var1.size();

                for(int var6 = 0; var6 < var5; ++var6) {
                    DemoAllType var7 = var1.get(var6);
                    var3.setString(1, var7.getFieldStr());
                    var3.setInt(2, var7.getFieldInt());
                    var3.setInt(3, var7.getFieldIntObj());
                    var3.setLong(4, var7.getFieldLong());
                    var3.setLong(5, var7.getFieldLongObj());
                    var3.setFloat(6, var7.getFieldFloat());
                    var3.setFloat(7, var7.getFieldFloatObj());
                    var3.setDouble(8, var7.getFieldDouble());
                    var3.setDouble(9, var7.getFieldDoubleObj());
                    var3.setBoolean(10, var7.isFieldBoolean());
                    var3.setBoolean(11, var7.getFieldBooleanObj());
                    var3.setDate(12, Convertor.toJavaSqlDate(var7.getFieldDate()));
                    var3.setTime(13, Convertor.toJavaSqlTime(var7.getFieldTime()));
                    var3.setTimestamp(14, Convertor.toJavaSqlTimestamp(var7.getFieldDateTime()));
                    var3.setByte(15, var7.getFieldByte());
                    var3.setByte(16, var7.getFieldByteObj());
                    var3.setShort(17, var7.getFieldShort());
                    var3.setShort(18, var7.getFieldShortObj());
                    var3.setString(19, Convertor.toJavaLangString(var7.getFieldChar()));
                    var3.setString(20, Convertor.toJavaLangString(var7.getFieldCharObj()));
                    var3.setBytes(21, var7.getFieldByteArray());
                    var3.setBigDecimal(22, var7.getFieldBigDecimal());
                    if (hassedIdValue) {
                        var3.setLong(23, var7.getId());
                    }
                    var3.addBatch();
                }

                int[] var12 = var3.executeBatch();
                if (!hassedIdValue) {
                    ResultSet rsIds = var3.getGeneratedKeys();
                    if (rsIds != null) {
                        int index = 0;
                        while (rsIds.next()) {
                            var1.get(index++).setId(rsIds.getLong(1));
                        }
                        rsIds.close();
                    }
                }
                if (var4) {
                    var2.commit();
                    var2.setAutoCommit(true);
                }

                int[] var8 = var12;
                return var8;
            } finally {
                var2.close();
            }
        }
    }

    public int insert(DemoAllType var1) throws Exception {
        StatementSession var2 = this.getStatementSession();

        int var7;
        try {
            boolean var5 = this.hasIdValue(var1.getId());
            PreparedStatement var3;
            if (var5) {
                var3 = var2.prepareStatement("INSERT INTO demo_all_type (field_str,field_int,field_int_obj,field_long,field_long_obj,field_float,field_float_obj,field_double,field_double_obj,field_boolean,field_boolean_obj,field_date,field_time,field_date_time,field_byte,field_byte_obj,field_short,field_short_obj,field_char,field_char_obj,field_byte_array,field_big_decimal,id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
            } else {
                var3 = var2.prepareStatement("INSERT INTO demo_all_type (field_str,field_int,field_int_obj,field_long,field_long_obj,field_float,field_float_obj,field_double,field_double_obj,field_boolean,field_boolean_obj,field_date,field_time,field_date_time,field_byte,field_byte_obj,field_short,field_short_obj,field_char,field_char_obj,field_byte_array,field_big_decimal) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", 1);
            }

            var3.setString(1, var1.getFieldStr());
            var3.setInt(2, var1.getFieldInt());
            var3.setInt(3, var1.getFieldIntObj());
            var3.setLong(4, var1.getFieldLong());
            var3.setLong(5, var1.getFieldLongObj());
            var3.setFloat(6, var1.getFieldFloat());
            var3.setFloat(7, var1.getFieldFloatObj());
            var3.setDouble(8, var1.getFieldDouble());
            var3.setDouble(9, var1.getFieldDoubleObj());
            var3.setBoolean(10, var1.isFieldBoolean());
            var3.setBoolean(11, var1.getFieldBooleanObj());
            var3.setDate(12, Convertor.toJavaSqlDate(var1.getFieldDate()));
            var3.setTime(13, Convertor.toJavaSqlTime(var1.getFieldTime()));
            var3.setTimestamp(14, Convertor.toJavaSqlTimestamp(var1.getFieldDateTime()));
            var3.setByte(15, var1.getFieldByte());
            var3.setByte(16, var1.getFieldByteObj());
            var3.setShort(17, var1.getFieldShort());
            var3.setShort(18, var1.getFieldShortObj());
            var3.setString(19, Convertor.toJavaLangString(var1.getFieldChar()));
            var3.setString(20, Convertor.toJavaLangString(var1.getFieldCharObj()));
            var3.setBytes(21, var1.getFieldByteArray());
            var3.setBigDecimal(22, var1.getFieldBigDecimal());
            if (var5) {
                var3.setLong(23, var1.getId());
            }

            int var4 = var3.executeUpdate();
            if (!var5) {
                ResultSet var6 = var3.getGeneratedKeys();
                if (var6 != null) {
                    if (var6.next()) {
                        var1.setId(var6.getLong(1));
                    }

                    var6.close();
                }
            }

            var7 = var4;
        } finally {
            var2.close();
        }

        return var7;
    }

    public List<DemoAllType> query(String var1) throws Exception {
        ResultSet var2 = this.execute(fillSqlField(var1));
        if (var2 == null) {
            return Constants.EMPTY_LIST;
        } else {
            String var3 = this.getFieldsSql(var1);
            JdbcFieldSetFunc var4 = (JdbcFieldSetFunc)FIELD_SET_FUNCS.get(var3);
            if (var4 == null) {
                var4 = this.getSqlFieldSetFunc(var2);
                FIELD_SET_FUNCS.putIfAbsent(var3, var4);
            }

            ArrayList var5 = new ArrayList();

            while(var2.next()) {
                DemoAllType var6 = new DemoAllType();
                var4.set(var6, var2);
                var5.add(var6);
            }

            return var5;
        }
    }

    private JdbcFieldSetFunc<DemoAllType> getSqlFieldSetFunc(ResultSet var1) throws SQLException {
        ResultSetMetaData var2 = var1.getMetaData();
        int var3 = var2.getColumnCount();
        ArrayList var4 = new ArrayList();

        for(int var5 = 1; var5 <= var3; ++var5) {
            var4.add(var2.getColumnName(var5));
        }

        return JdbcDaoRegister.instance().getFieldSetFunc(DemoAllType.class, var4);
    }

    private static String fillSqlField(String sql) {
        sql = sql.trim();
        int len = sql.length() > 7?7:sql.length();
        if (!sql.substring(0, len).toLowerCase(Locale.ENGLISH).startsWith("select ")) {
            sql = "select id,field_str from demo_all_type " + sql;
        }
        return sql;
    }

    public List<DemoAllType> query(String var1, QueryParam[] var2) throws Exception {
        try {
            ResultSet var3 = this.execute(fillSqlField(var1), var2);
            if (var3 == null) {
                List var12 = Constants.EMPTY_LIST;
                return var12;
            } else {
                String var4 = this.getFieldsSql(var1);
                JdbcFieldSetFunc var5 = (JdbcFieldSetFunc)FIELD_SET_FUNCS.get(var4);
                if (var5 == null) {
                    var5 = this.getSqlFieldSetFunc(var3);
                    FIELD_SET_FUNCS.put(var4, this.getSqlFieldSetFunc(var3));
                }

                ArrayList var6 = new ArrayList();

                while(var3.next()) {
                    DemoAllType var7 = new DemoAllType();
                    var5.set(var7, var3);
                    var6.add(var7);
                }

                ArrayList var11 = var6;
                return var11;
            }
        } finally {
            this.closeStatmentSession();
        }
    }

    public List<DemoAllType> query(String var1, Object... var2) throws Exception {
        List var4;
        try {
            ResultSet var3 = this.execute(fillSqlField(var1), var2);
            if (var3 != null) {
                String var12 = this.getFieldsSql(var1);
                JdbcFieldSetFunc var5 = (JdbcFieldSetFunc)FIELD_SET_FUNCS.get(var12);
                if (var5 == null) {
                    var5 = this.getSqlFieldSetFunc(var3);
                    FIELD_SET_FUNCS.putIfAbsent(var12, this.getSqlFieldSetFunc(var3));
                }

                ArrayList var6 = new ArrayList();

                while(var3.next()) {
                    DemoAllType var7 = new DemoAllType();
                    var5.set(var7, var3);
                    var6.add(var7);
                }

                ArrayList var11 = var6;
                return var11;
            }

            var4 = Constants.EMPTY_LIST;
        } finally {
            this.closeStatmentSession();
        }

        return var4;
    }

    public DemoAllType findOne(String var1) throws Exception {
        List var2 = this.query(fillSqlField(var1));
        return CollectionUtils.isEmpty(var2) ? null : (DemoAllType)var2.get(0);
    }

    public DemoAllType findOne(String var1, QueryParam[] var2) throws Exception {
        List var3 = this.query(fillSqlField(var1), var2);
        return CollectionUtils.isEmpty(var3) ? null : (DemoAllType)var3.get(0);
    }

    public DemoAllType findOne(String var1, Object[] var2) throws Exception {
        List var3 = this.query(fillSqlField(var1), var2);
        return CollectionUtils.isEmpty(var3) ? null : (DemoAllType)var3.get(0);
    }

    public int updateById(DemoAllType var1) throws Exception {
        StatementSession var2 = this.getStatementSession();

        int var5;
        try {
            String var3 = "UPDATE demo_all_type SET field_str=?,field_int=?,field_int_obj=?,field_long=?,field_long_obj=?,field_float=?,field_float_obj=?,field_double=?,field_double_obj=?,field_boolean=?,field_boolean_obj=?,field_date=?,field_time=?,field_date_time=?,field_byte=?,field_byte_obj=?,field_short=?,field_short_obj=?,field_char=?,field_char_obj=?,field_byte_array=?,field_big_decimal=? where id=?";
            PreparedStatement var4 = var2.prepareStatement(var3);
            var4.setString(1, var1.getFieldStr());
            var4.setInt(2, var1.getFieldInt());
            var4.setInt(3, var1.getFieldIntObj());
            var4.setLong(4, var1.getFieldLong());
            var4.setLong(5, var1.getFieldLongObj());
            var4.setFloat(6, var1.getFieldFloat());
            var4.setFloat(7, var1.getFieldFloatObj());
            var4.setDouble(8, var1.getFieldDouble());
            var4.setDouble(9, var1.getFieldDoubleObj());
            var4.setBoolean(10, var1.isFieldBoolean());
            var4.setBoolean(11, var1.getFieldBooleanObj());
            var4.setDate(12, Convertor.toJavaSqlDate(var1.getFieldDate()));
            var4.setTime(13, Convertor.toJavaSqlTime(var1.getFieldTime()));
            var4.setTimestamp(14, Convertor.toJavaSqlTimestamp(var1.getFieldDateTime()));
            var4.setByte(15, var1.getFieldByte());
            var4.setByte(16, var1.getFieldByteObj());
            var4.setShort(17, var1.getFieldShort());
            var4.setShort(18, var1.getFieldShortObj());
            var4.setString(19, Convertor.toJavaLangString(var1.getFieldChar()));
            var4.setString(20, Convertor.toJavaLangString(var1.getFieldCharObj()));
            var4.setBytes(21, var1.getFieldByteArray());
            var4.setBigDecimal(22, var1.getFieldBigDecimal());
            var4.setLong(23, var1.getId());
            var5 = var4.executeUpdate();
        } finally {
            var2.close();
        }

        return var5;
    }

    public DemoAllType findById(Object var1) throws Exception {
        if (var1 == null) {
            return null;
        } else {
            StatementSession var2 = this.getStatementSession();

            DemoAllType var5;
            try {
                PreparedStatement var3 = var2.prepareStatement("select * from demo_all_type where id=?");
                var3.setLong(1, (Long)var1);
                ResultSet var4 = var3.executeQuery();
                if (!var4.next()) {
                    return null;
                }

                var5 = new DemoAllType();
                var5.setId(var4.getLong("id"));
                var5.setFieldStr(var4.getString("field_str"));
                var5.setFieldInt(var4.getInt("field_int"));
                var5.setFieldIntObj(var4.getInt("field_int_obj"));
                var5.setFieldLong(var4.getLong("field_long"));
                var5.setFieldLongObj(var4.getLong("field_long_obj"));
                var5.setFieldFloat(var4.getFloat("field_float"));
                var5.setFieldFloatObj(var4.getFloat("field_float_obj"));
                var5.setFieldDouble(var4.getDouble("field_double"));
                var5.setFieldDoubleObj(var4.getDouble("field_double_obj"));
                var5.setFieldBoolean(var4.getBoolean("field_boolean"));
                var5.setFieldBooleanObj(var4.getBoolean("field_boolean_obj"));
                var5.setFieldDate(Convertor.toJavaTimeLocalDate(var4.getDate("field_date")));
                var5.setFieldTime(Convertor.toJavaTimeLocalTime(var4.getTime("field_time")));
                var5.setFieldDateTime(Convertor.toJavaTimeLocalDateTime(var4.getTimestamp("field_date_time")));
                var5.setFieldByte(var4.getByte("field_byte"));
                var5.setFieldByteObj(var4.getByte("field_byte_obj"));
                var5.setFieldShort(var4.getShort("field_short"));
                var5.setFieldShortObj(var4.getShort("field_short_obj"));
                var5.setFieldChar(Convertor.toC(var4.getString("field_char")));
                var5.setFieldCharObj(Convertor.toJavaLangCharacter(var4.getString("field_char_obj")));
                var5.setFieldByteArray(var4.getBytes("field_byte_array"));
                var5.setFieldBigDecimal(var4.getBigDecimal("field_big_decimal"));
            } finally {
                var2.close();
            }

            return var5;
        }
    }

    public int delete(String var1) throws Exception {
        return super.delete(getFullDeleteSql(var1, "demo_all_type"));
    }

    public int delete(String var1, Object[] var2) throws Exception {
        return super.delete(getFullDeleteSql(var1, "demo_all_type"), var2);
    }

    public int delete(String var1, QueryParam[] var2) throws Exception {
        return super.delete(getFullDeleteSql(var1, "demo_all_type"), var2);
    }
}
