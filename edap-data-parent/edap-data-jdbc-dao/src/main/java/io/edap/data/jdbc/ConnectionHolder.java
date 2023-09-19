package io.edap.data.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static io.edap.log.helpers.Util.printError;

public interface ConnectionHolder {

    ThreadLocal<Connection> CONNECTION_LOCAL = new ThreadLocal<>();

    default Connection getConnection() throws SQLException {
        return CONNECTION_LOCAL.get();
    }

    default void setConnection(Connection con) {
        CONNECTION_LOCAL.set(con);
    }

    DataSource getDataSource();

    void setDataSource(DataSource dataSource);

    void releaseConnection(Connection con) throws SQLException;

    class SimpleConnectionHolder implements ConnectionHolder {

        private Connection con;

        private Connection currentCon;

        private DataSource ds;

        @Override
        public DataSource getDataSource() {
            return ds;
        }

        @Override
        public void setDataSource(DataSource dataSource) {
            this.ds = dataSource;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (this.con != null) {
                return con;
            }
            if ((currentCon == null || currentCon.isClosed()) && ds != null) {
                currentCon = ds.getConnection();
            }
            return currentCon;
        }

        @Override
        public void setConnection(Connection con) {
            this.con = con;
        }

        @Override
        public void releaseConnection(Connection con) throws SQLException {
            if (this.con == null) {
                con.close();
            }
        }
    }

}
