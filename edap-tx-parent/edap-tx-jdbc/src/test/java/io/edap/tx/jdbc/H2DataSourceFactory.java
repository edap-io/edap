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

package io.edap.tx.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * H2 in-memory DataSource 工厂 —— 给 tx-jdbc 集成测试用。
 *
 * <p>每个测试方法 {@code @BeforeEach} 调一次 {@link #create()},得到一个独立的
 * H2 数据库(URL 序列号保证不共享 schema 与数据)。{@code DB_CLOSE_DELAY=-1}
 * 让最后一个连接关闭前 schema 仍存活,便于跨多连接的 "commit 后重连 SELECT" 验证。</p>
 *
 * <p>典型模式:</p>
 * <pre>
 *   DataSource ds = H2DataSourceFactory.create();
 *   try (Connection c = ds.getConnection()) {
 *       c.createStatement().execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR(100))");
 *   }
 * </pre>
 */
final class H2DataSourceFactory {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private H2DataSourceFactory() {
    }

    static DataSource create() {
        HikariConfig cfg = new HikariConfig();
        // 序列号保证每个测试用独立 DB;DB_CLOSE_DELAY=-1 让最后一个连接关闭前 schema 不消
        cfg.setJdbcUrl("jdbc:h2:mem:tx_jdbc_test_" + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(2);
        cfg.setPoolName("tx-jdbc-test-" + SEQ.get());
        return new HikariDataSource(cfg);
    }
}