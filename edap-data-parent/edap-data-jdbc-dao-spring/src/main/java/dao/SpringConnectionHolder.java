package dao;

import io.edap.data.jdbc.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class SpringConnectionHolder implements ConnectionHolder {

    private DataSource ds;

    public Connection getConnection() {
        return DataSourceUtils.getConnection(ds);
    }

    @Override
    public DataSource getDataSource() {
        return ds;
    }

    @Override
    public void setDataSource(DataSource dataSource) {
        this.ds = dataSource;
    }

    @Override
    public void releaseConnection(Connection con) throws SQLException {
        DataSourceUtils.releaseConnection(con, ds);
    }
}
