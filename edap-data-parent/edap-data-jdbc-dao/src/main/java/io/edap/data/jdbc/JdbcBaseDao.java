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

package io.edap.data.jdbc;

import io.edap.data.QueryParam;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import static io.edap.log.helpers.Util.printError;

public abstract class JdbcBaseDao {

    protected Logger LOG = LoggerManager.getLogger(this.getClass());

    DataSource dataSource;

    private Connection con;

    static final ThreadLocal<StatementSession> STMT_SESSION_LOCAL =
            ThreadLocal.withInitial(() -> {
                StatementSession statementSession = new SingleStatementSession();
                return statementSession;
            });

    private ConnectionHolder connectionHolder;

    static ConnectionHolder CONNECTION_HOLDER;

    private StatementSession stmtSession;

    protected LimitDialect limitDialect;

    static {

        String spiProvider = System.getProperty("edap.edao.connection.holder");
        if (spiProvider == null || spiProvider.trim().length() <= 0) {
            String envHoder = System.getenv("edap.dao.connection.holder");
            if (StringUtil.isEmpty(envHoder)) {
                spiProvider = envHoder;
            }
        }


        ClassLoader managerClassLoader = JdbcBaseDao.class.getClassLoader();
        ServiceLoader<ConnectionHolder> loader;
        loader = ServiceLoader.load(ConnectionHolder.class, managerClassLoader);
        Iterator<ConnectionHolder> iterator = loader.iterator();
        List<ConnectionHolder> providers = new ArrayList<>();
        ConnectionHolder matcher = null;
        while (iterator.hasNext()) {
            ConnectionHolder provider = safelyInstantiate(iterator);
            if (provider != null) {
                providers.add(provider);
            }
            if (!StringUtil.isEmpty(spiProvider) && provider.getClass().getName().equals(provider)) {
                matcher = provider;
            }
        }
        if (matcher != null) {
            CONNECTION_HOLDER = matcher;
        } else {
            if (!providers.isEmpty()) {
                CONNECTION_HOLDER = providers.get(0);
            }
        }
    }

    private static ConnectionHolder safelyInstantiate(Iterator<ConnectionHolder> iterator) {
        try {
            ConnectionHolder provider = iterator.next();
            return provider;
        } catch (ServiceConfigurationError e) {
            printError("A EdapLog ConnectionHolder failed to instantiate:", e);
        }
        return null;
    }

    public JdbcBaseDao() {
        initConnectionHolder();
    }

    void initConnectionHolder() {
        if (CONNECTION_HOLDER != null) {
            connectionHolder = CONNECTION_HOLDER;
        } else {
            connectionHolder = new ConnectionHolder.SimpleConnectionHolder();
        }
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        if (connectionHolder == null) {
            connectionHolder = new ConnectionHolder.SimpleConnectionHolder();
        }
        if (connectionHolder != null) {
            connectionHolder.setDataSource(dataSource);
        }
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void setConnection(Connection con) {
        this.con = con;
        if (connectionHolder == null) {
            connectionHolder = new ConnectionHolder.SimpleConnectionHolder();
        }
        connectionHolder.setConnection(con);
    }

    public StatementSession getStatementSession() throws SQLException {
        if (stmtSession == null) {
            stmtSession = new SingleStatementSession();
            stmtSession.setConHolder(connectionHolder);
        } else {
            stmtSession.setConHolder(connectionHolder);
        }
//        StatementSession session = STMT_SESSION_LOCAL.get();
//        session.setConHolder(connectionHolder);
        return stmtSession;
    }

    protected void closeStatmentSession() {
        StatementSession session = STMT_SESSION_LOCAL.get();
        closeStatmentSession(session);
    }

    protected void closeStatmentSession(StatementSession session) {
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



    public static void setPreparedParams(PreparedStatement pstmt, QueryParam... params) throws SQLException {
        setPreparedParams(pstmt, 1, params);
    }

    public static void setPreparedParams(PreparedStatement pstmt, int startIndex, QueryParam... params) throws SQLException {
        if (CollectionUtils.isEmpty(params)) {
            return;
        }
        for (QueryParam param : params) {
            Object value = param.getParam();
            pstmt.setObject(startIndex, value);
            startIndex++;
        }
    }

    public static void setPreparedParams(PreparedStatement pstmt, Object... params) throws SQLException {
        setPreparedParams(pstmt, 1, params);
    }

    public static void setPreparedParams(PreparedStatement pstmt, int startIndex, Object... params) throws SQLException {
        if (CollectionUtils.isEmpty(params)) {
            return;
        }
        for (Object param : params) {
            pstmt.setObject(startIndex, param);
            startIndex++;
        }
    }
}
