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
import io.edap.data.JdbcEntityDao;
import io.edap.data.jdbc.test.entity.Demo;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EntityDaoTest extends AbstractDaoTest {

    @Test
    public void testInsert() {
        String createSql = "create table demo \n" +
                "( \n" +
                "id bigint primary key not null AUTO_INCREMENT, \n" +
                "age varchar(200) null, \n" +
                "create_time bigint, \n" +
                "local_date_time bigint" +
                ")";
        Connection con = null;
        try {
            con = openConnection();
            System.out.println("connect success");
            boolean dropResult = false;
            try {
                con.createStatement().execute("drop table IF EXISTS demo");
                dropResult = true;
            } catch (Exception e) {
                e.printStackTrace();
            }
            boolean result = false;
            try {
                con.createStatement().execute(createSql);
                result = true;
            } catch (Exception e) {
                e.printStackTrace();
            }
            assertTrue(result);
            JdbcEntityDao<Demo> dao = JdbcDaoRegister.instance().getEntityDao(Demo.class, "h2");
            dao.setConnection(con);
            Demo demo = new Demo();
            demo.setId(1);
            long createTime = System.currentTimeMillis();
            demo.setCreateTime(createTime);
            demo.setField1("23");
            long localTime = System.currentTimeMillis();
            demo.setLocalDateTime(System.currentTimeMillis());

            int row = dao.insert(demo);
            assertEquals(row > 0, true);

            con = openConnection();
            ResultSet rs = con.createStatement().executeQuery("select * from demo");
            while (rs.next()) {
                assertEquals(rs.getLong("id"), 1);
                assertEquals(rs.getString("age"), "23");
                assertEquals(rs.getLong("create_time"), createTime);
                assertEquals(rs.getLong("local_date_time"), localTime);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            close(con);
        }
    }
}
