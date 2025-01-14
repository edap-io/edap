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

package io.edap.data.jdbc.jdbc.test;

import io.edap.data.jdbc.JdbcFieldSetFunc;
import io.edap.data.jdbc.jdbc.test.entity.Demo;

import java.sql.ResultSet;
import java.sql.SQLException;

public class DemoFullSetFuncJdbc implements JdbcFieldSetFunc<Demo> {

    private static String COLUMN_NAMES = "id,create_time,local_datetime";
    @Override
    public void set(Demo demo, ResultSet rs) throws SQLException {
        demo.setId(rs.getInt("id"));
        demo.setCreateTime(rs.getLong("create_time"));
        demo.setLocalDateTime(rs.getLong("local_datetime"));
    }
}
