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

import io.edap.data.jdbc.DaoOption;
import io.edap.data.jdbc.JdbcDaoRegister;
import io.edap.data.jdbc.annotation.Column;
import io.edap.data.jdbc.annotation.GeneratedValue;
import io.edap.data.jdbc.annotation.Id;
import io.edap.data.jdbc.jdbc.test.entity.Demo;
import io.edap.data.jdbc.jdbc.test.entity.DemoIdMethodGeneratedValue;
import io.edap.data.jdbc.jdbc.test.entity.NoFieldDemo;
import io.edap.data.jdbc.model.ColumnsInfo;
import io.edap.data.jdbc.model.InsertInfo;
import io.edap.data.jdbc.model.JdbcInfo;
import io.edap.data.jdbc.model.UpdateInfo;
import io.edap.data.jdbc.util.DaoUtil;
import io.edap.data.jdbc.jdbc.test.entity.DaoUtilDemo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        InsertInfo sql = DaoUtil.getInsertSql(Demo.class, new DaoOption());

        System.out.println("insert SQL: " + sql.getInsertSql());

        InsertInfo insertInfo = DaoUtil.getInsertSql(null, new DaoOption());
        assertNotNull(insertInfo);
        assertEquals(insertInfo.getInsertSql(), EMPTY_STRING);
        assertNull(insertInfo.getGenerationType());

        insertInfo = DaoUtil.getInsertSql(NoIdEntity.class, new DaoOption());
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
        JdbcDaoRegister.instance().getFieldSetFunc(Demo.class, columns, "select id,age,create_time,local_date_time");
    }

    @Test
    public void testGetUpdateByIdSql() {
        UpdateInfo updateInfo = DaoUtil.getUpdateByIdSql(null, null);
        assertNotNull(updateInfo);
        assertEquals(updateInfo.getUpdateSql(), EMPTY_STRING);
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
        List<JdbcInfo> jdbcInfos = DaoUtil.getJdbcInfos(NoFieldDemo.class);
        assertEquals(jdbcInfos.size(), 0);

        jdbcInfos = DaoUtil.getJdbcInfos(HasBooleanFieldDemo.class);
        assertEquals(jdbcInfos.size(), 2);
    }

    @Test
    public void testGetBoxedName() {
        Class<?> cls = byte.class;
        String name = DaoUtil.getBoxedName(cls);
        assertEquals(name, toInternalName(Byte.class.getName()));

        cls = boolean.class;
        name = DaoUtil.getBoxedName(cls);
        assertEquals(name, toInternalName(Boolean.class.getName()));

        cls = char.class;
        name = DaoUtil.getBoxedName(cls);
        assertEquals(name, toInternalName(Character.class.getName()));

        cls = short.class;
        name = DaoUtil.getBoxedName(cls);
        assertEquals(name, toInternalName(Short.class.getName()));

        cls = int.class;
        name = DaoUtil.getBoxedName(cls);
        assertEquals(name, toInternalName(Integer.class.getName()));

        cls = float.class;
        name = DaoUtil.getBoxedName(cls);
        assertEquals(name, toInternalName(Float.class.getName()));

        cls = double.class;
        name = DaoUtil.getBoxedName(cls);
        assertEquals(name, toInternalName(Double.class.getName()));

        cls = long.class;
        name = DaoUtil.getBoxedName(cls);
        assertEquals(name, toInternalName(Long.class.getName()));
    }

    @Test
    public void testGetTableName() {
        String tbName = DaoUtil.getTableName(DaoUtilDemo.class);
        assertEquals(tbName, "dao_demo");
    }

    @Test
    public void testToUnderScore() {
        String camel = "";
        String underScore = DaoUtil.toUnderScore(camel);
        assertEquals("", underScore);

        camel = null;
        underScore = DaoUtil.toUnderScore(camel);
        assertEquals("", underScore);

        camel = "testTCP";
        underScore = DaoUtil.toUnderScore(camel);
        assertEquals("test_tcp", underScore);

        camel = "testT";
        underScore = DaoUtil.toUnderScore(camel);
        assertEquals("testt", underScore);

        camel = "testTCPUdp";
        underScore = DaoUtil.toUnderScore(camel);
        assertEquals("test_tcp_udp", underScore);
    }

    @Test
    public void testIsAutoIncrementType() {
        Assertions.assertFalse(DaoUtil.isAutoIncrementType(null));
    }

    @Test
    public void testGetFieldColumn() throws NoSuchMethodException, NoSuchFieldException, InvocationTargetException, IllegalAccessException {
        Method m = DaoUtil.class.getDeclaredMethod("getFieldColumn", new Class[]{Field.class, Map.class});
        Field namefield = DaoUtilDemo.class.getDeclaredField("name");
        Field countField = DaoUtilDemo.class.getDeclaredField("count");
        Map<String, Method> getMethods = new HashMap<>();
        Method getMethod = DaoUtilDemo.class.getMethod("getCount", new Class[0]);
        getMethods.put("count", getMethod);

        m.setAccessible(true);
        Column column = (Column)m.invoke(null, namefield, getMethods);
        assertEquals(column.name(), "last_name");

        column = (Column)m.invoke(null, countField, getMethods);
        assertEquals(column.name(), "buy_count");
    }

    @Test
    public void setGetFieldId() throws NoSuchMethodException, NoSuchFieldException, InvocationTargetException, IllegalAccessException {
        Method m = DaoUtil.class.getDeclaredMethod("getFieldId", new Class[]{Field.class, Map.class});
        Field idField = DaoUtilDemo.class.getDeclaredField("id");
        Map<String, Method> getMethods = new HashMap<>();
        Method getMethod = DaoUtilDemo.class.getMethod("getId", new Class[0]);
        getMethods.put("id", getMethod);

        m.setAccessible(true);
        Id id = (Id)m.invoke(null, idField, getMethods);
        assertNotNull(id);
    }

    @Test
    public void testGetMethodIdAnnotation() throws NoSuchMethodException, NoSuchFieldException, InvocationTargetException, IllegalAccessException {
        Method m = DaoUtil.class.getDeclaredMethod("getMethodIdAnnotation", new Class[]{Method.class});
        Method idMethod = DaoUtilDemo.class.getDeclaredMethod("getId", new Class[0]);

        Id id = (Id)m.invoke(null, idMethod);
        assertNotNull(id);
    }

    @Test
    public void testIsIdField() throws NoSuchMethodException, NoSuchFieldException, InvocationTargetException, IllegalAccessException {
        Method m = DaoUtil.class.getDeclaredMethod("isIdField", new Class[]{Field.class, Method.class});
        Field idField = DaoUtilDemo.class.getDeclaredField("id");
        Method idMethod = DaoUtilDemo.class.getDeclaredMethod("getId", new Class[0]);

        boolean isId = (boolean)m.invoke(null, idField, idMethod);
        assertTrue(isId);
    }

    @Test
    public void testGetFieldGeneratedValue() throws NoSuchMethodException, NoSuchFieldException, InvocationTargetException, IllegalAccessException {
        Method m = DaoUtil.class.getDeclaredMethod("getFieldGeneratedValue", new Class[]{
                Field.class, Map.class
        });

        Field idField = DaoUtilDemo.class.getDeclaredField("id");
        Map<String, Method> getMethods = new HashMap<>();
        Method getMethod = DaoUtilDemo.class.getMethod("getId", new Class[0]);
        m.setAccessible(true);
        GeneratedValue gv = (GeneratedValue)m.invoke(null, idField, getMethods);
        assertNotNull(gv);

        idField = DemoIdMethodGeneratedValue.class.getDeclaredField("id");
        getMethod = DemoIdMethodGeneratedValue.class.getMethod("getId", new Class[0]);
        getMethods.put("id", getMethod);
        m.setAccessible(true);
        gv = (GeneratedValue)m.invoke(null, idField, getMethods);
        assertNotNull(gv);
    }

    @Test
    public void testSearchFieldByName() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method m = DaoUtil.class.getDeclaredMethod("searchFieldByName", new Class[]{
                List.class, String.class
        });
        m.setAccessible(true);
        Object v = m.invoke(null, null, "name");
        assertNull(v);
    }

    @Test
    public void testGetColumns() {
        ColumnsInfo ci = DaoUtil.getColumns(NoFieldDemo.class, new DaoOption());
        assertNotNull(ci);
        assertEquals(ci.getColumns().size(), 0);
    }
}
