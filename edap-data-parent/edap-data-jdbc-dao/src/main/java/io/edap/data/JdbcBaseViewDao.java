package io.edap.data;

import javax.sql.DataSource;
import java.sql.Connection;

public abstract class JdbcBaseViewDao {

    DataSource dataSource;

    protected String databaseType;

    Connection con;

    static final ThreadLocal<StatementSession> STMT_SESSION_LOCAL =
            new ThreadLocal<StatementSession>() {
                @Override
                protected StatementSession initialValue() {
                    StatementSession statementSession = new SingleStatementSession();
                    return statementSession;
                }
            };

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void setConnection(Connection con) {
        this.con = con;
    }

    public String getDatabaseType() {
        return databaseType;
    }

    public StatementSession getStatementSession() {
        StatementSession session = STMT_SESSION_LOCAL.get();
        if (session.getDataSource() == null) {
            session.setDataSource(dataSource);
        }
        if (con != null && session.getDataSource() == null) {
            session.setConnection(con);
        }
        return session;
    }


}
