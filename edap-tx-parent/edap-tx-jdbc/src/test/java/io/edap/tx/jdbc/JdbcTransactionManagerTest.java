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

import com.zaxxer.hikari.HikariDataSource;
import io.edap.tx.Synchronization;
import io.edap.tx.TransactionDefinition;
import io.edap.tx.TransactionStatus;
import io.edap.tx.TxScope;
import io.edap.tx.exception.TransactionSystemException;
import io.edap.tx.isolation.Isolation;
import io.edap.tx.propagation.Propagation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DataSourceTransactionManager} 集成测试 —— H2 in-memory 真 SQL 验证
 * 7×3 决策矩阵 + commit/rollback + 连接归还 + 隔离级别透传 的真实行为。
 *
 * <p>与 {@code edap-tx} 模块的 {@code CommitRollbackTest} / {@code PropagationDecisionTest}
 * 互补:那两类测抽象层(用 mock resource),本类测 JDBC 真实路径(真 connection +
 * 真 commit/rollback)。</p>
 *
 * <p><b>重要:不要用 try-with-resources 关闭 manager 给的 Connection</b>——
 * manager 在 commit/rollback 时才释放连接。测试中如需做 INSERT/SELECT,
 * 用 {@code Connection c = ...; try (Statement st = c.createStatement())} 模式,
 * Statement 由 try-with-resources 关闭,Connection 留给 manager 释放。</p>
 */
class JdbcTransactionManagerTest {

    private DataSource dataSource;
    private DataSourceTransactionManager tm;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = H2DataSourceFactory.create();
        tm = new DataSourceTransactionManager(dataSource);
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR(100))");
        }
    }

    @AfterEach
    void tearDown() {
        TxScope.clear();
        if (dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
        }
    }

    /** 在 manager 给的 connection 上执行一条 INSERT,留给 manager 释放 connection。 */
    private static void insert(Connection c, int id, String v) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO t(id, v) VALUES (" + id + ", '" + v + "')");
        }
    }

    /** 在新拿的 connection 上 SELECT COUNT,验证数据可见性。 */
    private int countById(DataSource ds, int id) throws SQLException {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t WHERE id=" + id)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    /** 在新拿的 connection 上 SELECT COUNT,验证所有行数。 */
    private int countAll(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    // ============ 1. 简单 commit ============

    @Test
    @DisplayName("简单 commit:insert 后 commit → 重连 SELECT 能查到")
    void simple_commit_persists() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        Connection con = ((JdbcTransactionResource) s.resource()).getConnection();
        insert(con, 1, "a");
        tm.commit(s);

        assertEquals(1, countById(dataSource, 1), "commit 后数据应可见");
    }

    // ============ 2. 简单 rollback ============

    @Test
    @DisplayName("简单 rollback:insert 后 rollback → 重连 SELECT 查不到")
    void simple_rollback_dropsInsert() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        Connection con = ((JdbcTransactionResource) s.resource()).getConnection();
        insert(con, 2, "b");
        tm.rollback(s);

        assertEquals(0, countById(dataSource, 2), "rollback 后数据应不可见");
    }

    // ============ 3. REQUIRED 嵌套共享 connection ============

    @Test
    @DisplayName("REQUIRED 嵌套:内外层共享同一个 Connection")
    void required_nested_sharesConnection() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        Connection outerCon = ((JdbcTransactionResource) outer.resource()).getConnection();
        Connection innerCon = ((JdbcTransactionResource) inner.resource()).getConnection();
        assertSame(outerCon, innerCon, "REQUIRED 嵌套应共享同一 Connection");

        insert(outerCon, 3, "outer");
        insert(innerCon, 4, "inner");

        tm.commit(inner);  // 嵌套内层 — 只 decrement,不真正 commit
        tm.commit(outer);  // 真正 commit

        assertEquals(2, countAll(dataSource));
    }

    // ============ 4. REQUIRED 嵌套 + 外层 rollback ============

    @Test
    @DisplayName("REQUIRED 嵌套 + 外层 rollback:整事务回滚,所有嵌套写入都被丢弃")
    void required_nested_outerRollback_dropsAll() throws Exception {
        // Spring 同构语义:REQUIRED 嵌套 + 内层异常/rollback 等价于整事务 rollback
        // (整事务共享同一 connection,rollback 不能只回滚部分)
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        Connection outerCon = ((JdbcTransactionResource) outer.resource()).getConnection();
        insert(outerCon, 10, "outer");

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        Connection innerCon = ((JdbcTransactionResource) inner.resource()).getConnection();
        insert(innerCon, 11, "inner");

        tm.commit(inner);  // 嵌套内层 commit 只 decrement
        tm.rollback(outer);  // 外层 rollback → 整事务回滚

        assertEquals(0, countById(dataSource, 10), "外层 rollback 应回滚外层写入");
        assertEquals(0, countById(dataSource, 11), "外层 rollback 应回滚内层写入");
    }

    // ============ 5. REQUIRES_NEW 独立 commit ============

    @Test
    @DisplayName("REQUIRES_NEW:内层独立 commit,外层 rollback 不影响内层")
    void requiresNew_innerCommit_survivesOuterRollback() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        Connection outerCon = ((JdbcTransactionResource) outer.resource()).getConnection();
        insert(outerCon, 20, "outer-drop");

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW).build());
        Connection innerCon = ((JdbcTransactionResource) inner.resource()).getConnection();
        assertNotSame(outerCon, innerCon, "REQUIRES_NEW 应拿到不同的 Connection");
        insert(innerCon, 21, "inner-keep");

        tm.commit(inner);   // 内层独立 commit
        tm.rollback(outer); // 外层 rollback 不影响已 commit 的内层

        assertEquals(1, countById(dataSource, 21), "REQUIRES_NEW 内层 commit 应独立可见");
        assertEquals(0, countById(dataSource, 20), "外层 rollback 数据应被丢弃");
    }

    // ============ 6. REQUIRES_NEW 独立 rollback ============

    @Test
    @DisplayName("REQUIRES_NEW:内层 rollback 不影响外层 commit")
    void requiresNew_innerRollback_doesNotAffectOuter() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        Connection outerCon = ((JdbcTransactionResource) outer.resource()).getConnection();
        insert(outerCon, 30, "outer-keep");

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW).build());
        Connection innerCon = ((JdbcTransactionResource) inner.resource()).getConnection();
        insert(innerCon, 31, "inner-drop");

        tm.rollback(inner);  // 内层独立 rollback
        tm.commit(outer);

        assertEquals(1, countById(dataSource, 30), "外层 commit 数据应可见");
        assertEquals(0, countById(dataSource, 31), "内层 rollback 数据应被丢弃");
    }

    // ============ 7. NESTED savepoint ============

    @Test
    @DisplayName("NESTED:内层 rollback 只回滚到 savepoint,外层数据保留")
    void nested_savepoint_onlyDropsNested() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        Connection outerCon = ((JdbcTransactionResource) outer.resource()).getConnection();
        insert(outerCon, 40, "before-savepoint");

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.NESTED).build());
        Connection innerCon = ((JdbcTransactionResource) inner.resource()).getConnection();
        insert(innerCon, 41, "inside-nested");

        tm.rollback(inner);  // NESTED 路径 → rollback(savepoint)

        // 外层仍可正常 commit
        tm.commit(outer);

        assertEquals(1, countById(dataSource, 40), "savepoint 之外的数据应保留");
        assertEquals(0, countById(dataSource, 41), "savepoint 之内的 NESTED 写入应被回滚");
    }

    // ============ 8. rollbackOnly 触发 rollback ============

    @Test
    @DisplayName("rollbackOnly:setRollbackOnly 后 commit 走真实 rollback")
    void rollbackOnly_routesToRollback() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        Connection con = ((JdbcTransactionResource) s.resource()).getConnection();
        insert(con, 50, "rolled-back");

        s.setRollbackOnly();
        tm.commit(s);  // rollbackOnly → 走 rollback 路径

        assertEquals(0, countById(dataSource, 50), "rollbackOnly 应真实回滚数据");
    }

    // ============ 9. beforeCommit 异常触发 rollback ============

    @Test
    @DisplayName("beforeCommit 抛异常:触发 rollback,数据未提交")
    void beforeCommitException_triggersRollback() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        Connection con = ((JdbcTransactionResource) s.resource()).getConnection();
        insert(con, 60, "rolled-back");

        TxScope.addSynchronization(new Synchronization() {
            @Override
            public void beforeCommit() {
                throw new RuntimeException("simulated before-commit failure");
            }
        });

        assertThrows(TransactionSystemException.class, () -> tm.commit(s));
        assertEquals(0, countById(dataSource, 60), "beforeCommit 异常时应真实回滚数据");
    }

    // ============ 10. commit 抛 SQLException 触发 rollback ============

    @Test
    @DisplayName("commit 抛 SQLException:manager 触发 rollback,数据未提交")
    void commitSqlException_triggersRollback() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        JdbcTransactionResource res = (JdbcTransactionResource) s.resource();
        Connection con = res.getConnection();
        insert(con, 70, "rolled-back");

        // 模拟 commit 失败:先把 connection 物理关闭,再让 manager commit
        con.close();

        assertThrows(TransactionSystemException.class, () -> tm.commit(s));
        assertEquals(0, countById(dataSource, 70), "commit 失败后数据应不可见");
    }

    // ============ 11. 隔离级别透传 ============

    @Test
    @DisplayName("isolation=SERIALIZABLE 透传到 Connection")
    void isolationSerializable_propagatesToConnection() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED)
                .isolation(Isolation.SERIALIZABLE)
                .build());

        Connection con = ((JdbcTransactionResource) s.resource()).getConnection();
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, con.getTransactionIsolation(),
                "Isolation.SERIALIZABLE 应被透传到 Connection");

        tm.commit(s);
    }

    @Test
    @DisplayName("isolation=DEFAULT 不调 setTransactionIsolation(保持 DB 默认)")
    void isolationDefault_doesNotChangeIsolation() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED)
                .isolation(Isolation.DEFAULT)
                .build());

        Connection con = ((JdbcTransactionResource) s.resource()).getConnection();
        // H2 默认 READ_COMMITTED;不是 SERIALIZABLE 即可证明 DEFAULT 没强制改
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, con.getTransactionIsolation(),
                "Isolation.DEFAULT 应保留 H2 默认隔离");

        tm.commit(s);
    }

    // ============ 12. commit 后连接已归还池 ============

    @Test
    @DisplayName("commit 后连接:isClosed=true")
    void commit_releasesConnection() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        Connection con = ((JdbcTransactionResource) s.resource()).getConnection();
        assertFalse(con.isClosed(), "commit 前连接应活跃");
        assertFalse(con.getAutoCommit(), "commit 前 autoCommit 应为 false");

        insert(con, 80, "release-test");
        tm.commit(s);

        assertTrue(con.isClosed(), "commit 后连接应已关闭(回池)");
    }

    // ============ 13. rollback 后连接已归还池 ============

    @Test
    @DisplayName("rollback 后连接:isClosed=true")
    void rollback_releasesConnection() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        Connection con = ((JdbcTransactionResource) s.resource()).getConnection();

        tm.rollback(s);
        assertTrue(con.isClosed(), "rollback 后连接应已关闭(回池)");
    }

    // ============ 14. 综合:嵌套 + 隔离级别独立设置 ============

    @Test
    @DisplayName("综合:嵌套 + 隔离级别 + 完整 commit 路径")
    void integration_nestedWithIsolation() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED)
                .isolation(Isolation.SERIALIZABLE)
                .build());
        Connection outerCon = ((JdbcTransactionResource) outer.resource()).getConnection();
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, outerCon.getTransactionIsolation());

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW)
                .isolation(Isolation.READ_COMMITTED)
                .build());
        Connection innerCon = ((JdbcTransactionResource) inner.resource()).getConnection();
        assertNotSame(outerCon, innerCon, "REQUIRES_NEW 应新拿连接");
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, innerCon.getTransactionIsolation(),
                "REQUIRES_NEW 内层隔离级别独立设置");

        insert(outerCon, 90, "outer-rc");
        insert(innerCon, 91, "inner-rc");

        tm.commit(inner);
        tm.commit(outer);

        assertEquals(2, countAll(dataSource), "两层隔离级别独立生效,数据全可见");
    }
}