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

import io.edap.data.DaoOption;
import io.edap.data.JdbcDaoRegister;
import io.edap.data.jdbc.test.entity.Demo;
import io.edap.data.model.InsertInfo;
import io.edap.data.model.JdbcInfo;
import io.edap.data.model.UpdateInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.edap.data.util.DaoUtil.*;
import static io.edap.util.AsmUtil.toInternalName;
import static io.edap.util.Constants.EMPTY_STRING;
import static org.junit.jupiter.api.Assertions.*;

public class DaoUtilTest {

    static class NoIdEntity {
        private String name;

        private int height;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getHeight() {
            return height;
        }

        public void setHeight(int height) {
            this.height = height;
        }
    }

    @Test
    public void testGetInsertSql() {
        InsertInfo sql = getInsertSql(Demo.class, new DaoOption());

        System.out.println("insert SQL: " + sql.getInsertSql());

        InsertInfo insertInfo = getInsertSql(null, new DaoOption());
        assertNotNull(insertInfo);
        assertEquals(insertInfo.getInsertSql(), EMPTY_STRING);
        assertNull(insertInfo.getGenerationType());

        insertInfo = getInsertSql(NoIdEntity.class, new DaoOption());
        assertNotNull(insertInfo);
        assertEquals(insertInfo.getInsertSql(), insertInfo.getNoIdInsertSql());
        assertEquals(insertInfo.getInsertSql(), "INSERT INTO no_id_entity (name,height) VALUES (?,?)");
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

    @Test
    public void testGetUpdateByIdSql() {
        UpdateInfo updateInfo = getUpdateByIdSql(null);
        assertNotNull(updateInfo);
        assertEquals(updateInfo.getUpdateSql(), EMPTY_STRING);
    }

    static class NoFieldDemo {

    }

    static class HasBooleanFieldDemo {
        private boolean success;
        private int code;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }
    }

    @Test
    public void testGetJdbcInfos() {
        List<JdbcInfo> jdbcInfos = getJdbcInfos(NoFieldDemo.class);
        assertEquals(jdbcInfos.size(), 0);

        jdbcInfos = getJdbcInfos(HasBooleanFieldDemo.class);
        assertEquals(jdbcInfos.size(), 2);
    }

    @Test
    public void testGetBoxedName() {
        Class<?> cls = byte.class;
        String name = getBoxedName(cls);
        assertEquals(name, toInternalName(Byte.class.getName()));

        cls = boolean.class;
        name = getBoxedName(cls);
        assertEquals(name, toInternalName(Boolean.class.getName()));

        cls = char.class;
        name = getBoxedName(cls);
        assertEquals(name, toInternalName(Character.class.getName()));

        cls = short.class;
        name = getBoxedName(cls);
        assertEquals(name, toInternalName(Short.class.getName()));

        cls = int.class;
        name = getBoxedName(cls);
        assertEquals(name, toInternalName(Integer.class.getName()));

        cls = float.class;
        name = getBoxedName(cls);
        assertEquals(name, toInternalName(Float.class.getName()));

        cls = double.class;
        name = getBoxedName(cls);
        assertEquals(name, toInternalName(Double.class.getName()));

        cls = long.class;
        name = getBoxedName(cls);
        assertEquals(name, toInternalName(Long.class.getName()));
    }
}
