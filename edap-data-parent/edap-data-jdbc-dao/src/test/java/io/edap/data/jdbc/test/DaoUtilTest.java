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

package io.edap.data.jdbc.test;

import io.edap.data.JdbcDaoRegister;
import io.edap.data.jdbc.test.entity.Demo;
import io.edap.data.model.InsertInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.edap.data.util.DaoUtil.getInsertSql;

public class DaoUtilTest {

    @Test
    public void testGetInsertSql() {
        InsertInfo sql = getInsertSql(Demo.class);

        System.out.println("insert SQL: " + sql.getInsertSql());
    }

    @Test
    public void testGenerateSetFunc() {
        List<String> columns = new ArrayList<>();
        columns.add("id");
        columns.add("age");
        columns.add("create_time");
        columns.add("local_date_time");
        JdbcDaoRegister.instance().getFieldSetFunc(Demo.class, columns);
    }
}
