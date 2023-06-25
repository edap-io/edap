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
import io.edap.data.jdbc.test.entity.DemoIntId;
import io.edap.data.jdbc.test.entity.DemoLongId;
import io.edap.data.jdbc.test.entity.DemoLongObjId;

public class DaoTest {

    public static void main(String[] args) {

        JdbcEntityDao<Demo> demoDao = JdbcDaoRegister.instance()
                .getEntityDao(Demo.class, "Postgresql");

        JdbcEntityDao<DemoIntId> demoIntIdDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoIntId.class, "Postgresql");

        JdbcEntityDao<DemoLongId> demoILongIdDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoLongId.class, "Postgresql");

        JdbcEntityDao<DemoLongObjId> demoILongObjIdDao = JdbcDaoRegister.instance()
                .getEntityDao(DemoLongObjId.class, "Postgresql");
    }
}
