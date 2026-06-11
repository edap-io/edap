//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package edao.io.edap.data.jdbc.jdbc.test.entity;

import io.edap.data.PageResult;
import io.edap.data.QueryParam;
import io.edap.data.jdbc.DaoOption;
import io.edap.data.jdbc.JdbcBaseEntityDao;
import io.edap.data.jdbc.JdbcDaoRegister;
import io.edap.data.jdbc.JdbcEntityDao;
import io.edap.data.jdbc.JdbcFieldSetFunc;
import io.edap.data.jdbc.LimitQueryInfo;
import io.edap.data.jdbc.StatementSession;
import io.edap.data.jdbc.jdbc.test.entity.DemoIntId;
import io.edap.data.jdbc.model.TypeConvertorValue;
import io.edap.data.jdbc.util.DialectFactory;
import io.edap.util.CollectionUtils;
import io.edap.util.Constants;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DemoIntIdJdbcEntityDao extends JdbcBaseEntityDao implements JdbcEntityDao<DemoIntId> {
    private DaoOption daoOption;
    static Map<String, JdbcFieldSetFunc<DemoIntId>> FIELD_SET_FUNCS = new ConcurrentHashMap();

    public DemoIntIdJdbcEntityDao() {
        this((DaoOption)null);
    }

    public DemoIntIdJdbcEntityDao(DaoOption var1) {
        this.daoOption = var1;
        this.limitDialect = DialectFactory.createLimitDialect(var1);
    }

    private static String fillSqlField(String var0) {
        if (var0 == null) {
            var0 = "";
        } else {
            var0 = var0.trim();
        }

        if (!DialectFactory.isSelectStart(var0)) {
            var0 = "SELECT id,create_time,local_date_time FROM demo_int_id " + var0;
        }

        return var0;
    }

    public int[] insert(List<DemoIntId> var1) throws Exception {
        StatementSession var2 = this.getStatementSession();

        Object var3;
        try {
            if (!CollectionUtils.isEmpty(var1)) {
                boolean var5 = true;
                PreparedStatement var4;
                if (var5) {
                    var4 = var2.prepareStatement("INSERT INTO demo_int_id (create_time,local_date_time,id) VALUES (?,?,?)");
                } else {
                    var4 = var2.prepareStatement("INSERT INTO demo_int_id (create_time,local_date_time) VALUES (?,?)", 1);
                }

                boolean var6 = var2.getAutoCommit();
                if (var6) {
                    var2.setAutoCommit(false);
                }

                var4.clearBatch();
                int var7 = var1.size();

                for(int var8 = 0; var8 < var7; ++var8) {
                    DemoIntId var9 = (DemoIntId)var1.get(var8);
                    var4.setLong(1, var9.getCreateTime());
                    var4.setLong(2, var9.getLocalDateTime());
                    if (var5) {
                        var4.setInt(3, var9.getId());
                    }

                    var4.addBatch();
                }

                int[] var11 = var4.executeBatch();
                if (!var5) {
                    ResultSet var12 = var4.getGeneratedKeys();
                    if (var12 != null) {
                        int var13 = 0;

                        while(var12.next()) {
                            ((DemoIntId)var1.get(var13++)).setId(var12.getInt(1));
                        }

                        var12.close();
                    }
                }

                if (var6) {
                    var2.commit();
                    var2.setAutoCommit(true);
                }

                int[] var10 = var11;
                return var10;
            }

            var3 = null;
        } finally {
            var2.close();
        }

        return (int[])var3;
    }

    public int insert(DemoIntId var1) throws Exception {
        StatementSession var2 = this.getStatementSession();

        int var7;
        try {
            boolean var5 = this.hasIdValue(var1.getId());
            PreparedStatement var3;
            if (var5) {
                var3 = var2.prepareStatement("INSERT INTO demo_int_id (create_time,local_date_time,id) VALUES (?,?,?)");
            } else {
                var3 = var2.prepareStatement("INSERT INTO demo_int_id (create_time,local_date_time) VALUES (?,?)", 1);
            }

            var3.setLong(1, var1.getCreateTime());
            var3.setLong(2, var1.getLocalDateTime());
            if (var5) {
                var3.setInt(3, var1.getId());
            }

            int var4 = var3.executeUpdate();
            if (!var5) {
                ResultSet var6 = var3.getGeneratedKeys();
                if (var6 != null) {
                    if (var6.next()) {
                        var1.setId(var6.getInt(1));
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

    private JdbcFieldSetFunc<DemoIntId> getSqlFieldSetFunc(ResultSet var1, String var2) throws SQLException {
        ResultSetMetaData var3 = var1.getMetaData();
        int var4 = var3.getColumnCount();
        ArrayList var5 = new ArrayList();

        for(int var6 = 1; var6 <= var4; ++var6) {
            var5.add(var3.getColumnName(var6));
        }

        return JdbcDaoRegister.instance().getFieldSetFunc(DemoIntId.class, var5, var2);
    }

    public List<DemoIntId> query(String var1) throws Exception {
        return this.query(var1, Constants.EMPTY_ARRAY);
    }

    public List<DemoIntId> query(String var1, QueryParam[] var2) throws Exception {
        List var5;
        try {
            String var3 = fillSqlField(var1);
            ResultSet var4 = this.execute(var3, var2);
            if (var4 != null) {
                String var12 = this.getFieldsSql(var3);
                JdbcFieldSetFunc var6 = (JdbcFieldSetFunc)FIELD_SET_FUNCS.get(var12);
                if (var6 == null) {
                    var6 = this.getSqlFieldSetFunc(var4, var12);
                    FIELD_SET_FUNCS.put(var12, var6);
                }

                ArrayList var7 = new ArrayList();

                while(var4.next()) {
                    DemoIntId var8 = new DemoIntId();
                    var6.set(var8, var4);
                    var7.add(var8);
                }

                ArrayList var13 = var7;
                return var13;
            }

            var5 = Constants.EMPTY_LIST;
        } finally {
            this.closeStatmentSession();
        }

        return var5;
    }

    public List<DemoIntId> query(String var1, Object... var2) throws Exception {
        List var5;
        try {
            String var3 = fillSqlField(var1);
            ResultSet var4 = this.execute(var3, var2);
            if (var4 != null) {
                String var12 = this.getFieldsSql(var3);
                JdbcFieldSetFunc var6 = (JdbcFieldSetFunc)FIELD_SET_FUNCS.get(var12);
                if (var6 == null) {
                    var6 = this.getSqlFieldSetFunc(var4, var12);
                    FIELD_SET_FUNCS.putIfAbsent(var12, var6);
                }

                ArrayList var7 = new ArrayList();

                while(var4.next()) {
                    DemoIntId var8 = new DemoIntId();
                    var6.set(var8, var4);
                    var7.add(var8);
                }

                ArrayList var13 = var7;
                return var13;
            }

            var5 = Constants.EMPTY_LIST;
        } finally {
            this.closeStatmentSession();
        }

        return var5;
    }

    public List<DemoIntId> query(String var1, int var2, int var3) throws Exception {
        return this.query(var1, var2, var3, Constants.EMPTY_ARRAY);
    }

    public List<DemoIntId> query(String var1, int var2, int var3, Object... var4) throws Exception {
        if (var2 < 0) {
            var2 = 0;
        }

        if (var3 < 1) {
            var3 = 1;
        }

        var1 = fillSqlField(var1);
        StatementSession var5 = this.getStatementSession();

        ArrayList var11;
        try {
            LimitQueryInfo var6 = this.limitDialect.process(var1, var2, var3);
            PreparedStatement var7 = var5.prepareStatement(var6.getSql());
            setPreparedParams(var7, var4);
            setPreparedParams(var7, var4.length + 1, var6.getParams());
            ResultSet var8 = var7.executeQuery();
            String var9 = this.getFieldsSql(var1);
            JdbcFieldSetFunc var10 = (JdbcFieldSetFunc)FIELD_SET_FUNCS.get(var9);
            if (var10 == null) {
                var10 = this.getSqlFieldSetFunc(var8, var9);
                FIELD_SET_FUNCS.putIfAbsent(var9, var10);
            }

            var11 = new ArrayList();

            while(var8.next()) {
                DemoIntId var12 = new DemoIntId();
                var10.set(var12, var8);
                var11.add(var12);
            }
        } finally {
            this.closeStatmentSession(var5);
        }

        return var11;
    }

    public List<DemoIntId> query(String var1, int var2, int var3, QueryParam... var4) throws Exception {
        if (var2 < 0) {
            var2 = 0;
        }

        if (var3 < 1) {
            var3 = 1;
        }

        var1 = fillSqlField(var1);
        StatementSession var5 = this.getStatementSession();

        ArrayList var11;
        try {
            LimitQueryInfo var6 = this.limitDialect.process(var1, var2, var3);
            PreparedStatement var7 = var5.prepareStatement(var6.getSql());
            setPreparedParams(var7, var4);
            setPreparedParams(var7, var4.length + 1, var6.getParams());
            ResultSet var8 = var7.executeQuery();
            String var9 = this.getFieldsSql(var1);
            JdbcFieldSetFunc var10 = (JdbcFieldSetFunc)FIELD_SET_FUNCS.get(var9);
            if (var10 == null) {
                var10 = this.getSqlFieldSetFunc(var8, var9);
                FIELD_SET_FUNCS.putIfAbsent(var9, var10);
            }

            var11 = new ArrayList();

            while(var8.next()) {
                DemoIntId var12 = new DemoIntId();
                var10.set(var12, var8);
                var11.add(var12);
            }
        } finally {
            this.closeStatmentSession(var5);
        }

        return var11;
    }

    public PageResult<DemoIntId> queryPage(String var1, String var2, int var3, int var4) throws Exception {
        return this.queryPage(var1, var2, var3, var4, Constants.EMPTY_ARRAY);
    }

    public PageResult<DemoIntId> queryPage(String var1, String var2, int var3, int var4, Object... var5) throws Exception {
        if (var4 < 1) {
            var4 = 1;
        }

        int var6;
        if (var3 < 1) {
            var6 = 0;
        } else {
            var6 = (var3 - 1) * var4;
        }

        PageResult var7 = new PageResult();
        var7.setPageSize(var4);
        var1 = fillSqlField(var1);
        StatementSession var8 = this.getStatementSession();

        try {
            String var9 = DialectFactory.buildTotalSql(var1, "demo_int_id", this.daoOption);
            PreparedStatement var10 = var8.prepareStatement(var9);
            setPreparedParams(var10, var5);
            ResultSet var11 = var10.executeQuery();
            if (var11.next()) {
                var7.setTotal(var11.getInt(1));
            }

            LimitQueryInfo var12 = this.limitDialect.process(var1, var6, var4, var2);
            var10 = var8.prepareStatement(var12.getSql());
            setPreparedParams(var10, var5);
            setPreparedParams(var10, var5.length + 1, var12.getParams());
            var11 = var10.executeQuery();
            String var13 = this.getFieldsSql(var1);
            JdbcFieldSetFunc var14 = (JdbcFieldSetFunc)FIELD_SET_FUNCS.get(var13);
            if (var14 == null) {
                var14 = this.getSqlFieldSetFunc(var11, var13);
                FIELD_SET_FUNCS.putIfAbsent(var13, var14);
            }

            ArrayList var15 = new ArrayList();

            while(var11.next()) {
                DemoIntId var16 = new DemoIntId();
                var14.set(var16, var11);
                var15.add(var16);
            }

            var7.setDataList(var15);
        } finally {
            this.closeStatmentSession(var8);
        }

        return var7;
    }

    public PageResult<DemoIntId> queryPage(String var1, String var2, int var3, int var4, QueryParam... var5) throws Exception {
        if (var4 < 1) {
            var4 = 1;
        }

        int var6;
        if (var3 < 1) {
            var6 = 0;
        } else {
            var6 = (var3 - 1) * var4;
        }

        PageResult var7 = new PageResult();
        var7.setPageSize(var4);
        var1 = fillSqlField(var1);
        StatementSession var8 = this.getStatementSession();

        try {
            String var9 = DialectFactory.buildTotalSql(var1, "demo_int_id", this.daoOption);
            PreparedStatement var10 = var8.prepareStatement(var9);
            setPreparedParams(var10, var5);
            ResultSet var11 = var10.executeQuery();
            if (var11.next()) {
                var7.setTotal(var11.getInt(1));
            }

            LimitQueryInfo var12 = this.limitDialect.process(var1, var6, var4, var2);
            var10 = var8.prepareStatement(var12.getSql());
            setPreparedParams(var10, var5);
            setPreparedParams(var10, var5.length + 1, var12.getParams());
            var11 = var10.executeQuery();
            String var13 = this.getFieldsSql(var1);
            JdbcFieldSetFunc var14 = (JdbcFieldSetFunc)FIELD_SET_FUNCS.get(var13);
            if (var14 == null) {
                var14 = this.getSqlFieldSetFunc(var11, var13);
                FIELD_SET_FUNCS.putIfAbsent(var13, var14);
            }

            ArrayList var15 = new ArrayList();

            while(var11.next()) {
                DemoIntId var16 = new DemoIntId();
                var14.set(var16, var11);
                var15.add(var16);
            }

            var7.setDataList(var15);
        } finally {
            this.closeStatmentSession(var8);
        }

        return var7;
    }

    public DemoIntId findOne(String var1) throws Exception {
        List var2 = this.query(var1);
        return CollectionUtils.isEmpty(var2) ? null : (DemoIntId)var2.get(0);
    }

    public DemoIntId findOne(String var1, QueryParam[] var2) throws Exception {
        List var3 = this.query(var1, var2);
        return CollectionUtils.isEmpty(var3) ? null : (DemoIntId)var3.get(0);
    }

    public DemoIntId findOne(String var1, Object[] var2) throws Exception {
        List var3 = this.query(var1, var2);
        return CollectionUtils.isEmpty(var3) ? null : (DemoIntId)var3.get(0);
    }

    public int updateById(DemoIntId var1) throws Exception {
        StatementSession var2 = this.getStatementSession();

        int var5;
        try {
            String var3 = "UPDATE demo_int_id SET create_time=?,local_date_time=? where id=?";
            PreparedStatement var4 = var2.prepareStatement(var3);
            var4.setLong(1, var1.getCreateTime());
            var4.setLong(2, var1.getLocalDateTime());
            var4.setInt(3, var1.getId());
            var5 = var4.executeUpdate();
        } finally {
            var2.close();
        }

        return var5;
    }

    public int update(Map<String, Object> var1, Map<String, Object> var2) throws Exception {
        if (!CollectionUtils.isEmpty(var1) && !CollectionUtils.isEmpty(var2)) {
            StatementSession var3 = this.getStatementSession();

            int var20;
            try {
                StringBuilder var4 = new StringBuilder("update demo_int_id set ");
                int var5 = 0;
                Object[] var6 = new Object[var1.size() + var2.size()];
                boolean var7 = false;

                for(Map.Entry var9 : var1.entrySet()) {
                    if (var7) {
                        var4.append(',');
                    } else {
                        var7 = true;
                    }


                    var4.append((String)var9.getKey()).append('=');
                    Object obj = var9.getValue();
                    if (obj instanceof TypeConvertorValue) {
                        TypeConvertorValue tcv = (TypeConvertorValue)obj;
                        var4.append(tcv.getJdbcPlaceholder());
                        var6[var5++] = tcv.getValue();
                    } else {
                        var4.append('?');
                        var6[var5++] = var9.getValue();
                    }
                }

                var4.append(" where ");
                var7 = false;

                for(Map.Entry var21 : var2.entrySet()) {
                    if (var7) {
                        var4.append(" and ");
                    } else {
                        var7 = true;
                    }

                    var4.append((String)var21.getKey()).append("=?");
                    var6[var5++] = var21.getValue();
                }

                PreparedStatement var19 = var3.prepareStatement(var4.toString());
                var5 = 1;

                for(Object var12 : var6) {
                    var19.setObject(var5++, var12);
                }

                var20 = var19.executeUpdate();
            } finally {
                var3.close();
            }

            return var20;
        } else {
            return 0;
        }
    }

    public DemoIntId findById(Object var1) throws Exception {
        if (var1 == null) {
            return null;
        } else {
            StatementSession var2 = this.getStatementSession();

            DemoIntId var5;
            try {
                PreparedStatement var3 = var2.prepareStatement("SELECT create_time,id,local_date_time FROM demo_int_id WHERE id=?");
                var3.setInt(1, (Integer)var1);
                ResultSet var4 = var3.executeQuery();
                if (!var4.next()) {
                    return null;
                }

                var5 = new DemoIntId();
                var5.setCreateTime(var4.getLong(1));
                var5.setId(var4.getInt(2));
                var5.setLocalDateTime(var4.getLong(3));
            } finally {
                var2.close();
            }

            return var5;
        }
    }

    public int delete(String var1) throws Exception {
        return super.delete(getFullDeleteSql(var1, "demo_int_id"));
    }

    public int delete(String var1, Object[] var2) throws Exception {
        return super.delete(getFullDeleteSql(var1, "demo_int_id"), var2);
    }

    public int delete(String var1, QueryParam[] var2) throws Exception {
        return super.delete(getFullDeleteSql(var1, "demo_int_id"), var2);
    }
}
