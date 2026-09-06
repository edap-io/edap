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

    void releaseConnection() throws SQLException;

    /**
     * 当前 holder 是否处于事务上下文。
     *
     * <ul>
     *   <li>返回 true:session 拿到的 con 是 tx 共享 con,生命周期由 tx manager 负责,
     *       session 不能 close 它(否则会把共享 con 关掉,后续 dao 调用报 "Connection is closed"
     *       —— ESTYLR 2026-09-05 事故根因)</li>
     *   <li>返回 false:session 拿到的 con 是 session 独占的,需要 session 自己 close 归还 ds
     *       (否则 con 泄漏,HikariCP 池逐步涨满 —— ESTYLR 2026-09-06 反馈)</li>
     * </ul>
     *
     * 默认 false —— SimpleConnectionHolder 永远不参与 tx。
     * tx-aware holder (TxConnectionHolder) 覆盖为 {@code TxScope.currentStatus() != null}
     * 且 resource 是 {@code JdbcTransactionResource}。
     */
    default boolean isInTransaction() {
        return false;
    }

    class SimpleConnectionHolder implements ConnectionHolder {

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
            if ((currentCon == null || currentCon.isClosed()) && ds != null) {
                currentCon = ds.getConnection();
            }
            return currentCon;
        }

        @Override
        public void setConnection(Connection con) {
        }

        @Override
        public void releaseConnection() throws SQLException {
            if (this.currentCon != null) {
                this.currentCon.close();
            }
        }
    }

}
