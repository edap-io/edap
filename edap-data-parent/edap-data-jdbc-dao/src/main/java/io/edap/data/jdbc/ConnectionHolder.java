package io.edap.data.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static io.edap.log.helpers.Util.printError;

public interface ConnectionHolder {

    ThreadLocal<Connection> CONNECTION_LOCAL = new ThreadLocal<>();

    default Connection getConnection() {
        return CONNECTION_LOCAL.get();
    }

    default void setConnect(Connection con) {
        CONNECTION_LOCAL.set(con);
    }

    DataSource getDataSource();

    void setDataSource(DataSource dataSource);

    void releaseConnection(Connection con) throws SQLException;

    class SimpleConnectionHolder implements ConnectionHolder {

        @Override
        public DataSource getDataSource() {
            return null;
        }

        @Override
        public void setDataSource(DataSource dataSource) {

        }

        @Override
        public void releaseConnection(Connection con) throws SQLException {
            if (con != null) {
                con.close();
            }
        }
    }


}
