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

package io.edap.data.jdbc.test.dao;

import io.edap.data.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.edap.util.Constants.EMPTY_LIST;

public class DemoMapDao extends JdbcBaseMapDao implements JdbcMapDao {
    @Override
    public List<Map<String, Object>> getList(String sql) throws Exception {
        try {
            ResultSet rs = execute(sql);
            if (rs == null) {
                return EMPTY_LIST;
            }
            List<Map<String, Object>> list = new ArrayList<>();
            ResultSetMetaData metaData = rs.getMetaData();
            int count = metaData.getColumnCount();
            while (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                for (int i=1;i<=count;i++) {
                    map.put(metaData.getColumnName(i), rs.getObject(i));
                }
            }
            return list;
        } finally {
            closeStatmentSession();
        }
    }

    @Override
    public List<Map<String, Object>> getList(String sql, QueryParam... params) throws Exception {
        return null;
    }

    @Override
    public Map<String, Object> getMap(String sql) throws Exception {
        return null;
    }

    @Override
    public Map<String, Object> getMap(String sql, QueryParam... params) throws Exception {
        return null;
    }

    @Override
    public int update(String sql) throws Exception {
        return 0;
    }

    @Override
    public int update(String sql, QueryParam... params) throws Exception {
        return 0;
    }
}
