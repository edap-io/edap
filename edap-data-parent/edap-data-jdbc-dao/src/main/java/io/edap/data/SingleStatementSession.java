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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

public class SingleStatementSession implements StatementSession {

    static Logger LOG = LoggerManager.getLogger(SingleStatementSession.class);

    private DataSource dataSource;
    private Connection con;

    private boolean daoConnection;
    private Map<String, PreparedStatement> preparedStmts = new HashMap<>();

    private Connection getConnection() throws SQLException {
        if (con == null) {
            con = dataSource.getConnection();
        }
        return con;
    }

    public void setConnection(Connection con) {
        this.con = con;
        this.daoConnection = true;
    }

    @Override
    public Statement createStatement() throws SQLException {
        Connection con = getConnection();
        return con.createStatement();
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return getConnection().getAutoCommit();
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        getConnection().setAutoCommit(autoCommit);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
//        PreparedStatement pstmt = preparedStmts.get(sql);
//        if (pstmt == null) {
//            pstmt = getConnection().prepareStatement(sql);
//            //preparedStmts.put(sql, pstmt);
//        }
//        return pstmt;
        return getConnection().prepareStatement(sql);
    }

    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        String key = sql + "_" + autoGeneratedKeys;
        PreparedStatement pstmt = preparedStmts.get(key);
        if (pstmt == null) {
            pstmt = getConnection().prepareStatement(sql, autoGeneratedKeys);
            preparedStmts.put(key, pstmt);
        }
        return pstmt;
    }

    @Override
    public void close() {
        try {
            preparedStmts.clear();
            if (!daoConnection) {
                Connection con = getConnection();
                con.close();
            }
            this.con = null;
        } catch (Throwable e) {
            LOG.warn("SingleStatementSession close error", e);
        }
    }

    public void closePreparedStatement(PreparedStatement pstmt) {
        try {
            pstmt.close();
        } catch (Throwable e) {
            LOG.warn("PreparedStatement close error", e);
        }
    }

    @Override
    public void close(boolean closeConnection) {
        try {
            if (closeConnection) {
                if (!daoConnection) {
                    Connection con = getConnection();
                    con.close();
                }
            }
        } catch (Throwable e) {
            LOG.warn("SingleStatementSession close error", e);
        } finally {
            preparedStmts.clear();
            this.con = null;
        }
    }

    @Override
    public void commit() throws SQLException {
        con.commit();
    }

    @Override
    public DataSource getDataSource() {
        return dataSource;
    }

    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }
}
