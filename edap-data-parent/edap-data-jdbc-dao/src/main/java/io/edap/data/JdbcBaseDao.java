/*
 * Copyright 2020 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.data;

import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Locale;

public abstract class JdbcBaseDao {

    protected Logger LOG = LoggerManager.getLogger(this.getClass());

    protected String databaseType;

    DataSource dataSource;

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

    protected void closeStatmentSession() {
        StatementSession session = STMT_SESSION_LOCAL.get();
        if (session != null) {
            session.close();
        }
    }

    protected String getFieldsSql(String sql) {
        String lowerSql = sql.toLowerCase(Locale.ENGLISH);
        int index = lowerSql.indexOf("from");
        if (index == -1) {
            return "select * ";
        } else {
            return lowerSql.substring(0, index);
        }
    }

    protected ResultSet execute(final String sql) throws SQLException {
        PreparedStatement pstmt = getStatementSession().prepareStatement(sql);
        return pstmt.executeQuery();
    }

    protected ResultSet execute(final String sql, QueryParam... params) throws SQLException {
        PreparedStatement pstmt = getStatementSession().prepareStatement(sql);
        setPreparedParams(pstmt, params);
        return pstmt.executeQuery();
    }

    protected ResultSet execute(final String sql, Object... params) throws SQLException {
        PreparedStatement pstmt = getStatementSession().prepareStatement(sql);
        setPreparedParams(pstmt, params);
        return pstmt.executeQuery();
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

    public static void setPreparedParams(PreparedStatement pstmt, QueryParam... params) throws SQLException {
        if (CollectionUtils.isEmpty(params)) {
            return;
        }
        int index = 0;
        for (QueryParam param : params) {
            Object value = param.getParam();
            index++;
            pstmt.setObject(index, value);

        }
    }

    public static void setPreparedParams(PreparedStatement pstmt, Object... params) throws SQLException {
        if (CollectionUtils.isEmpty(params)) {
            return;
        }
        int index = 0;
        for (Object param : params) {
            index++;
            pstmt.setObject(index, param);

        }
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
                try {
                    pstmt.close();
                } catch (Exception e) {
                    LOG.warn("PreparedStatement close error!", e);
                }
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
                try {
                    pstmt.close();
                } catch (Exception e) {
                    LOG.warn("PreparedStatement close error!", e);
                }
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
                try {
                    pstmt.close();
                } catch (Exception e) {
                    LOG.warn("PreparedStatement close error!", e);
                }
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
                try {
                    pstmt.close();
                } catch (Exception e) {
                    LOG.warn("PreparedStatement close error!", e);
                }
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
