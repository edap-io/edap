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

package io.edap.data.jdbc.test.dao;

import io.edap.data.*;
import io.edap.data.jdbc.test.entity.Demo;
import io.edap.util.CollectionUtils;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.edap.util.Constants.EMPTY_LIST;


public class DemoEntityDao extends JdbcBaseDao implements JdbcEntityDao<Demo> {

    static Map<String, JdbcFieldSetFunc<Demo>> FIELD_SET_FUNCS = new ConcurrentHashMap<>();

    @Override
    public int insert(Demo d) throws Exception {
        StatementSession session = getStatementSession();
        int rows;
        try {
            PreparedStatement ps;
            if (d.getId() != null) {
                ps = session.prepareStatement("insert into demo (username,create_time,id) values (?,?,?,?)");
            }  else {
                ps = session.prepareStatement("insert into demo (username,create_time) values (?,?,?)", 1);
            }
            ps.setString(1, d.getField1());
            ps.setLong(2, d.getCreateTime());
            if (d.getId() != null) {
                ps.setLong(3, d.getId());
            }
            rows = ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs != null) {
                if (rs.next()) {
                    d.setId(rs.getInt(1));
                }
                rs.close();
            }

            return rows;
        } finally {
            session.close();
        }
    }

    @Override
    public int[] insert(List<Demo> demos) throws Exception {
        if (CollectionUtils.isEmpty(demos)) {
            return null;
        }
        StatementSession session = getStatementSession();
        try {

            PreparedStatement ps = session.prepareStatement("insert into demo (id,username,create_time) values (?,?,?,?)");
            boolean initAuto = session.getAutoCommit();
            if (initAuto) {
                session.setAutoCommit(false);
            }
            ps.clearBatch();
            int size = demos.size();
            for (int i=0;i<size;i++) {
                Demo d = demos.get(i);
                ps.setLong(1, d.getId());
                ps.setString(2, d.getField1());
                ps.setLong(3, d.getCreateTime());
                ps.addBatch();
            }
            int[] rows = ps.executeBatch();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs != null) {
                int i = 0;
                while (rs.next()) {
                    demos.get(i).setId(rs.getInt(1));
                    i++;
                }
                rs.close();
            }
            if (initAuto) {
                session.commit();
                session.setAutoCommit(true);
            }
            return rows;
        } finally {
            session.close();
        }
    }

    @Override
    public List<Demo> query(String sql) throws Exception {
        try {
            ResultSet rs = execute(sql, null);
            if (rs == null) {
                return EMPTY_LIST;
            }
            String fieldSql = getFieldsSql(sql);
            JdbcFieldSetFunc<Demo> func = FIELD_SET_FUNCS.get(fieldSql);
            if (func == null) {
                FIELD_SET_FUNCS.putIfAbsent(fieldSql, getSqlFieldSetFunc(rs));
            }
            List<Demo> demos = new ArrayList<>();
            while (rs.next()) {
                Demo demo = new Demo();
                func.set(demo, rs);
            }
            return demos;
        } finally {
            closeStatmentSession();
        }
    }

    private JdbcFieldSetFunc<Demo> getSqlFieldSetFunc(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int count = metaData.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            columns.add(metaData.getColumnName(i));
        }
        return JdbcDaoRegister.instance().getFieldSetFunc(Demo.class, columns);
    }

    @Override
    public List<Demo> query(String sql, QueryParam... params) throws Exception {
        try {
            ResultSet rs = execute(sql, params);
            if (rs == null) {
                return EMPTY_LIST;
            }
            String fieldSql = getFieldsSql(sql);
            JdbcFieldSetFunc<Demo> func = FIELD_SET_FUNCS.get(fieldSql);
            if (func == null) {
                func = getSqlFieldSetFunc(rs);
                FIELD_SET_FUNCS.putIfAbsent(fieldSql, getSqlFieldSetFunc(rs));
            }
            List<Demo> demos = new ArrayList<>();
            while (rs.next()) {
                Demo demo = new Demo();
                func.set(demo, rs);
                demos.add(demo);
            }

            return demos;
        } finally {
            closeStatmentSession();
        }
    }

    @Override
    public List<Demo> query(String sql, Object... params) throws Exception {
        try {
            ResultSet rs = execute(sql, params);
            if (rs == null) {
                return EMPTY_LIST;
            }
            String fieldSql = getFieldsSql(sql);
            JdbcFieldSetFunc<Demo> func = FIELD_SET_FUNCS.get(fieldSql);
            if (func == null) {
                func = getSqlFieldSetFunc(rs);
                FIELD_SET_FUNCS.putIfAbsent(fieldSql, getSqlFieldSetFunc(rs));
            }
            List<Demo> demos = new ArrayList<>();
            while (rs.next()) {
                Demo demo = new Demo();
                func.set(demo, rs);
                demos.add(demo);
            }

            return demos;
        } finally {
            closeStatmentSession();
        }
    }

    @Override
    public Demo findById(Object id) throws Exception {
        if (id == null) {
            return null;
        }
        StatementSession session = getStatementSession();
        try {
            PreparedStatement pstmt = session.prepareStatement("select * from demo where id=?");
            pstmt.setLong(1, (long)id);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                Demo demo = new Demo();
                demo.setId(rs.getInt("id"));
                demo.setCreateTime(rs.getLong("create_time"));
                demo.setField1(rs.getString("age"));
                demo.setLocalDateTime(rs.getLong("update_time"));
                return demo;
            }
        } finally {
            session.close();
        }
        return null;
    }

    @Override
    public Demo findOne(String sql) throws Exception {
        List<Demo> demos = query(sql);
        if (CollectionUtils.isEmpty(demos)) {
            return null;
        }
        return demos.get(0);
    }

    @Override
    public Demo findOne(String sql, QueryParam...  params) throws Exception {
        List<Demo> demos = query(sql, params);
        if (CollectionUtils.isEmpty(demos)) {
            return null;
        }
        return demos.get(0);
    }

    @Override
    public Demo findOne(String sql, Object... params) throws Exception {
        List<Demo> demos = query(sql, params);
        if (CollectionUtils.isEmpty(demos)) {
            return null;
        }
        return demos.get(0);
    }

    @Override
    public int updateById(Demo entity) throws Exception {
        StatementSession session = getStatementSession();
        try {
            String sql = "update demo set field=? where id=?";
            PreparedStatement pstmt = session.prepareStatement(sql);
            pstmt.setLong(1, entity.getCreateTime());
            pstmt.setString(2, entity.getField1());
            pstmt.setLong(3, entity.getCreateTime());
            pstmt.setLong(4, entity.getLocalDateTime());
            pstmt.setLong(5, entity.getId());
            return pstmt.executeUpdate();
        } finally {
            session.close();
        }

    }

    @Override
    public int delete(String sql) {
        return 0;
    }

    @Override
    public int delete(String sql, QueryParam... params) {
        return 0;
    }
}
