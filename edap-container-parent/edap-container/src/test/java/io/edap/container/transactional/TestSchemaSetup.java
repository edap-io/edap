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

package io.edap.container.transactional;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * tx 端到端集成测试用 H2 in-memory DataSource + t 表。
 * 与 edap-tx-jdbc 自己的 H2DataSourceFactory 解耦,各自独立 DB 序列。
 */
final class TestSchemaSetup {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private TestSchemaSetup() {
    }

    static DataSource create() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:tx_e2e_" + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(2);
        DataSource ds = new HikariDataSource(cfg);
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR(100))");
        }
        return ds;
    }

    static int countById(DataSource ds, int id) throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t WHERE id=" + id)) {
            if (!rs.next()) throw new IllegalStateException("empty rs");
            return rs.getInt(1);
        }
    }

    static int countAll(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
            if (!rs.next()) throw new IllegalStateException("empty rs");
            return rs.getInt(1);
        }
    }

    static void insert(DataSource ds, int id, String v) throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO t(id, v) VALUES (" + id + ", '" + v + "')");
        }
    }
}