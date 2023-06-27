/*
 * Copyright 2023 The edap Project
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

import io.edap.util.CollectionUtils;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

public class JdbcBaseMapDao implements JdbcMapDao {

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
        StatementSession session = STMT_SESSION_LOCAL.get();
        this.dataSource = dataSource;
        session.setDataSource(dataSource);
    }

    public void setConnection(Connection con) {
        StatementSession session = STMT_SESSION_LOCAL.get();
        session.setConnection(con);
    }

    public StatementSession getStatementSession() {
        return STMT_SESSION_LOCAL.get();
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

    protected void closeStatmentSession() {
        StatementSession session = STMT_SESSION_LOCAL.get();
        if (session != null) {
            session.close();
        }
    }

    @Override
    public List<Map<String, Object>> getList(String sql) throws Exception {
        try {
            ResultSet rs = execute(sql);
            List<Map<String, Object>> list = new ArrayList<>();
            ResultSetMetaData metaData = rs.getMetaData();
            int count = metaData.getColumnCount();
            while (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                for (int i=1;i<=count;i++) {
                    map.put(metaData.getColumnName(i), rs.getObject(i));
                }
                list.add(map);
            }
            return list;
        } finally {
            closeStatmentSession();
        }
    }

    @Override
    public List<Map<String, Object>> getList(String sql, QueryParam... params) throws Exception {
        try {
            ResultSet rs = execute(sql, params);
            List<Map<String, Object>> list = new ArrayList<>();
            ResultSetMetaData metaData = rs.getMetaData();
            int count = metaData.getColumnCount();
            while (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                for (int i=1;i<=count;i++) {
                    map.put(metaData.getColumnName(i), rs.getObject(i));
                }
                list.add(map);
            }
            return list;
        } finally {
            closeStatmentSession();
        }
    }

    @Override
    public List<Map<String, Object>> getList(String sql, Object... params) throws Exception {
        try {
            ResultSet rs = execute(sql, params);
            List<Map<String, Object>> list = new ArrayList<>();
            ResultSetMetaData metaData = rs.getMetaData();
            int count = metaData.getColumnCount();
            while (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                for (int i=1;i<=count;i++) {
                    map.put(metaData.getColumnName(i), rs.getObject(i));
                }
                list.add(map);
            }
            return list;
        } finally {
            closeStatmentSession();
        }
    }

    @Override
    public Map<String, Object> getMap(String sql) throws Exception {

        try {
            ResultSet rs = execute(sql);
            Map<String, Object> map = new HashMap<>();
            ResultSetMetaData metaData = rs.getMetaData();
            int count = metaData.getColumnCount();
            if (rs.next()) {
                for (int i=1;i<=count;i++) {
                    map.put(metaData.getColumnName(i), rs.getObject(i));
                }
                return map;
            }
        } finally {
            closeStatmentSession();
        }
        return Collections.EMPTY_MAP;
    }

    @Override
    public Map<String, Object> getMap(String sql, QueryParam... params) throws Exception {
        try {
            ResultSet rs = execute(sql, params);
            ResultSetMetaData metaData = rs.getMetaData();
            int count = metaData.getColumnCount();
            if (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                for (int i=1;i<=count;i++) {
                    map.put(metaData.getColumnName(i), rs.getObject(i));
                }
                return map;
            }
        } finally {
            closeStatmentSession();
        }
        return Collections.EMPTY_MAP;
    }

    @Override
    public Map<String, Object> getMap(String sql, Object... params) throws Exception {
        try {
            ResultSet rs = execute(sql, params);
            ResultSetMetaData metaData = rs.getMetaData();
            int count = metaData.getColumnCount();
            if (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                for (int i=1;i<=count;i++) {
                    map.put(metaData.getColumnName(i), rs.getObject(i));
                }
                return map;
            }
        } finally {
            closeStatmentSession();
        }
        return Collections.EMPTY_MAP;
    }

    @Override
    public int update(String sql) throws Exception {
        try {
            PreparedStatement pstmt = getStatementSession().prepareStatement(sql);
            setPreparedParams(pstmt);
            return pstmt.executeUpdate();
        } finally {
            closeStatmentSession();
        }
    }

    @Override
    public int update(String sql, QueryParam... params) throws Exception {
        try {
            PreparedStatement pstmt = getStatementSession().prepareStatement(sql);
            setPreparedParams(pstmt, params);
            return pstmt.executeUpdate();
        } finally {
            closeStatmentSession();
        }
    }

    @Override
    public int update(String sql, Object... params) throws Exception {
        try {
            PreparedStatement pstmt = getStatementSession().prepareStatement(sql);
            setPreparedParams(pstmt, params);
            return pstmt.executeUpdate();
        } finally {
            closeStatmentSession();
        }
    }
}
