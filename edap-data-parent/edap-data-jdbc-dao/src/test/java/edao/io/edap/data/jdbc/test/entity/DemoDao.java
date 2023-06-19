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
import io.edap.data.jdbc.test.entity.Demo;
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

public class DemoDao extends JdbcBaseDao implements JdbcEntityDao<Demo> {
    static Map<String, JdbcFieldSetFunc<Demo>> FIELD_SET_FUNCS = new ConcurrentHashMap();

    public DemoDao() {
    }

    public int[] insert(List<Demo> var1) throws Exception {
        if (CollectionUtils.isEmpty(var1)) {
            return null;
        } else {
            StatementSession var2 = this.getStatementSession();

            try {
                PreparedStatement var3 = var2.prepareStatement("INSERT INTO demo (id,age,create_time,local_date_time) VALUES (?,?,?,?)");
                boolean var4 = var2.getAutoCommit();
                if (var4) {
                    var2.setAutoCommit(false);
                }

                var3.clearBatch();
                int var5 = var1.size();

                for(int var6 = 0; var6 < var5; ++var6) {
                    Demo var7 = (Demo)var1.get(0);
                    var3.setInt(1, var7.getId());
                    var3.setString(2, var7.getField1());
                    var3.setLong(3, var7.getCreateTime());
                    var3.setLong(4, var7.getLocalDateTime());
                    var3.addBatch();
                }

                int[] var12 = var3.executeBatch();
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

    public int insert(Demo var1) throws Exception {
        StatementSession var2 = this.getStatementSession();

        int var6;
        try {
            PreparedStatement var3 = var2.prepareStatement("INSERT INTO demo (id,age,create_time,local_date_time) VALUES (?,?,?,?)");
            var3.setInt(1, var1.getId());
            var3.setString(2, var1.getField1());
            var3.setLong(3, var1.getCreateTime());
            var3.setLong(4, var1.getLocalDateTime());
            int var4 = var3.executeUpdate();
            var6 = var4;
        } finally {
            var2.close();
        }

        return var6;
    }

    public List<Demo> query(String var1) throws Exception {
        ResultSet var2 = this.execute(var1);
        if (var2 == null) {
            return Constants.EMPTY_LIST;
        } else {
            String var3 = this.getFieldsSql(var1);
            JdbcFieldSetFunc var4 = (JdbcFieldSetFunc)FIELD_SET_FUNCS.get(var3);
            if (var4 == null) {
                FIELD_SET_FUNCS.putIfAbsent(var3, this.getSqlFieldSetFunc(var2));
            }

            ArrayList var5 = new ArrayList();

            while(var2.next()) {
                Demo var6 = new Demo();
                var4.set(var6, var2);
            }

            return var5;
        }
    }

    private JdbcFieldSetFunc<Demo> getSqlFieldSetFunc(ResultSet var1) throws SQLException {
        ResultSetMetaData var2 = var1.getMetaData();
        int var3 = var2.getColumnCount();
        ArrayList var4 = new ArrayList();

        for(int var5 = 1; var5 <= var3; ++var5) {
            var4.add(var2.getColumnName(var5));
        }

        return JdbcDaoRegister.instance().getFieldSetFunc(Demo.class, var4);
    }

    public List<Demo> query(String var1, QueryParam[] var2) throws Exception {
        List var4;
        try {
            ResultSet var3 = this.execute(var1, var2);
            if (var3 != null) {
                String var12 = this.getFieldsSql(var1);
                JdbcFieldSetFunc var5 = (JdbcFieldSetFunc)FIELD_SET_FUNCS.get(var12);
                if (var5 == null) {
                    var5 = this.getSqlFieldSetFunc(var3);
                    FIELD_SET_FUNCS.put(var12, this.getSqlFieldSetFunc(var3));
                }

                ArrayList var6 = new ArrayList();

                while(var3.next()) {
                    Demo var7 = new Demo();
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

    public Demo findOne(String var1) throws Exception {
        List var2 = this.query(var1);
        return CollectionUtils.isEmpty(var2) ? null : (Demo)var2.get(0);
    }

    public Demo findOne(String var1, QueryParam[] var2) throws Exception {
        List var3 = this.query(var1, var2);
        return CollectionUtils.isEmpty(var3) ? null : (Demo)var3.get(0);
    }

    public int updateById(Demo var1) throws Exception {
        StatementSession var2 = this.getStatementSession();

        int var5;
        try {
            String var3 = "UPDATE demo SET age=?,create_time=?,local_date_time=? where id=?";
            PreparedStatement var4 = var2.prepareStatement(var3);
            var4.setString(1, var1.getField1());
            var4.setLong(2, var1.getCreateTime());
            var4.setLong(3, var1.getLocalDateTime());
            var4.setInt(4, var1.getId());
            var5 = var4.executeUpdate();
        } finally {
            var2.close();
        }

        return var5;
    }

    public Demo findById(Object var1) throws Exception {
        if (var1 == null) {
            return null;
        } else {
            StatementSession var2 = this.getStatementSession();

            Demo var5;
            try {
                PreparedStatement var3 = var2.prepareStatement("select * from demo where id=?");
                var3.setInt(1, (Integer)var1);
                ResultSet var4 = var3.executeQuery();
                if (!var4.next()) {
                    return null;
                }

                var5 = new Demo();
                var5.setId(var4.getInt("id"));
                var5.setField1(var4.getString("age"));
                var5.setCreateTime(var4.getLong("create_time"));
                var5.setLocalDateTime(var4.getLong("local_date_time"));
            } finally {
                var2.close();
            }

            return var5;
        }
    }
}
