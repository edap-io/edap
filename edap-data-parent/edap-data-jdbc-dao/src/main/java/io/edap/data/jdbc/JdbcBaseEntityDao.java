package io.edap.data.jdbc;

import io.edap.data.QueryParam;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public abstract class JdbcBaseEntityDao extends JdbcBaseDao {

    protected boolean hasIdValue(Integer value) {
        return value != null && value > 0;
    }

    protected boolean hasIdValue(Long value) {
        return value != null && value > 0;
    }

    protected boolean hasIdValue(int value) {
        return value > 0;
    }

    protected boolean hasIdValue(long value) {
        return value > 0;
    }

    public int update(String sql) throws SQLException {
        StatementSession session = getStatementSession();
        try {
            Statement stmt = session.createStatement();
            int row = stmt.executeUpdate(sql);
            stmt.close();
            return row;
        } finally {
            session.close();
        }
    }

    public static String getFullDeleteSql(String sql, String tabeName) {
        sql = sql.trim();
        int len = sql.length() > 7?7:sql.length();
        if (!sql.substring(0, len).toLowerCase(Locale.ENGLISH).startsWith("delete ")) {
            sql = "delete from " + tabeName + " " + sql;
        }
        return sql;
    }

    public int update(final String sql, QueryParam... params) throws SQLException {
        StatementSession session = getStatementSession();
        try {
            boolean initAuto = session.getAutoCommit();
            if (initAuto) {
                session.setAutoCommit(false);
            }
            PreparedStatement pstmt = session.prepareStatement(sql);
            setPreparedParams(pstmt, params);
            int row = pstmt.executeUpdate();
            if (pstmt != null) {
                pstmt.close();
            }
            if (initAuto) {
                session.setAutoCommit(true);
            }
            return row;
        } finally {
            session.close();
        }
    }

    public int update(final String sql, Object... params) throws SQLException {
        StatementSession session = getStatementSession();
        try {
            boolean initAuto = session.getAutoCommit();
            if (initAuto) {
                session.setAutoCommit(false);
            }
            PreparedStatement pstmt = session.prepareStatement(sql);
            setPreparedParams(pstmt, params);
            int row = pstmt.executeUpdate();
            if (pstmt != null) {
                pstmt.close();
            }
            if (initAuto) {
                session.setAutoCommit(true);
            }
            return row;
        } finally {
            session.close();
        }
    }

    public int delete(String sql) throws Exception {
        StatementSession session = getStatementSession();
        try {
            Statement stmt = session.createStatement();
            int row = stmt.executeUpdate(sql);
            stmt.close();
            return row;
        } finally {
            session.close();
        }
    }

    public int delete(final String sql, QueryParam... params) throws Exception {
        StatementSession session = getStatementSession();
        try {
            boolean initAuto = session.getAutoCommit();
            if (initAuto) {
                session.setAutoCommit(false);
            }
            PreparedStatement pstmt = session.prepareStatement(sql);
            setPreparedParams(pstmt, params);
            int row = pstmt.executeUpdate();
            if (pstmt != null) {
                pstmt.close();
            }
            if (initAuto) {
                session.setAutoCommit(true);
            }
            return row;
        } finally {
            session.close();
        }
    }

    public int delete(final String sql, Object... params) throws Exception {
        StatementSession session = getStatementSession();
        try {
            boolean initAuto = session.getAutoCommit();
            if (initAuto) {
                session.setAutoCommit(false);
            }
            PreparedStatement pstmt = session.prepareStatement(sql);
            setPreparedParams(pstmt, params);
            int row = pstmt.executeUpdate();
            if (pstmt != null) {
                pstmt.close();
            }
            if (initAuto) {
                session.setAutoCommit(true);
            }
            return row;
        } finally {
            session.close();
        }
    }
}
