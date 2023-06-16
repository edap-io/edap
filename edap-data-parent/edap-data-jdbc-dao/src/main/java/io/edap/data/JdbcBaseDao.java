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

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public abstract class JdbcBaseDao {

    protected Logger LOG = LoggerManager.getLogger(this.getClass());

    protected String databaseType;

    DataSource dataSource;

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

    public String getDatabaseType() {
        return databaseType;
    }

    public StatementSession getStatementSession() {
        StatementSession session = STMT_SESSION_LOCAL.get();
        if (session.getDataSource() == null) {
            session.setDataSource(dataSource);
        }
        return session;
    }

    protected void closeStatmentSession() {
        StatementSession session = STMT_SESSION_LOCAL.get();
        if (session != null) {
            session.close();
        }
    }

    protected String getLimitSql(String sql, int offset, int limit) {
        return sql + " limit " + limit + " offset " + offset;
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

    protected ResultSet execute(final String sql, QueryParam... params) throws SQLException {
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

    public int delete(String sql) throws SQLException {
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

    public int delete(final String sql, QueryParam... params) throws SQLException {
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
