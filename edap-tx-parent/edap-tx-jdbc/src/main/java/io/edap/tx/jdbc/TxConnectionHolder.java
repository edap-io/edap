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

import io.edap.data.jdbc.ConnectionHolder;
import io.edap.tx.TransactionStatus;
import io.edap.tx.TxScope;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * edap-data-jdbc-dao 的 {@link ConnectionHolder} SPI 实现 —— 让 dao 自动从
 * 当前事务拿到共享连接,无需在每个 dao 方法里手动开/关事务。
 *
 * <p><b>接入路径</b>:dao 通过 ServiceLoader 加载本类,典型使用方式
 * {@code JdbcBaseDao#setDataSource(ds)} → 内部把 ds 透传给本 holder →
 * 业务方法 {@code dao.insert(...)} → holder.getConnection() 自动取到
 * 当前线程事务绑定的 {@link JdbcTransactionResource#getConnection()}。</p>
 *
 * <p><b>事务边界判定</b>:从 {@link TxScope#currentStatus()}
 * 取当前线程的 {@link TransactionStatus};若其 {@code resource} 是
 * {@link JdbcTransactionResource},证明当前在事务中,返回该共享连接 —— 多层嵌套
 * (REQUIRED / NESTED) 与同连接复用由 manager 自身保证。</p>
 *
 * <p><b>无事务路径</b>:不在事务中时(单语句直查场景,如 init 脚本 / 测试 setup),
 * fallback 到 {@link DataSource#getConnection()} <b>直拿一条新连接,不缓存</b>。
 * con 的生命周期由调用方(典型为 {@code SingleStatementSession})自己关 —
 * 见 {@link io.edap.data.jdbc.SingleStatementSession#close(boolean)}。</p>
 *
 * <p><b>为什么不缓存 (Phase 4 Plan B)</b>:本 holder 经 SPI 单例加载
 * ({@code JdbcBaseDao} 静态块里只 {@code ServiceLoader.load} 一次,所有 dao
 * 实例共用同一 holder)。早期版本用 instance field 缓存 con,T1 拿着 con-1
 * 在 dao 里跑查询、T2 进 getConnection 也读到 con-1、T1 完事
 * {@code closeStatmentSession} 把 con-1 关掉,T2 的 pstmt 下一次操作就报
 * PG "这个 statement 已经被关闭"(生产事故)。ThreadLocal 虽能解决但仍有跨
 * 线程泄漏风险,且 HikariCP 自身已做连接复用 —— 直接不缓存更稳,perf 损失
 * 基本可忽略(每次走 ds.getConnection() → HikariCP 从池里拿一根,池里可能
 * 正好是上一根归还的物理连接)。</p>
 *
 * <p><b>多数据源 + ds 自动同步 (Phase 4 Plan A)</b>:业务方把 dao 绑到 mainDs,
 * 但调用了 {@code @ManualTransaction(transactionManager="transactionManager_audit")}
 * 路由到 auditDs,事务结束后业务方若忘了重新 {@code setDataSource(auditDs)} 就调
 * 非事务 dao 方法,本 holder 仍会用旧的 mainDs 做 fallback,把数据写到错的库。
 * 解决:tx 路径里检测到 tx 资源的 ds 与 holder.dataSource 不一致时,自动同步
 * holder.dataSource = txDs。这样 tx 结束后的非事务调用就自然 fall 到 tx 用过
 * 的 ds,无需业务方手动 {@code setDataSource}。</p>
 *
 * <p><b>线程安全</b>:</p>
 * <ul>
 *   <li>{@link #dataSource} 跨线程共享(用 {@code volatile} 写可见)—— DataSource
 *       自身线程安全;Plan A 的 ds 切换走 {@code double-checked locking};</li>
 *   <li>事务路径(进 {@link TransactionStatus} 的 if 分支)天然隔离 —— status
 *       走 ThreadLocal,各线程独立;</li>
 *   <li>非事务路径不缓存,每次从 ds 拿新 —— 调用方(singleStatementSession)负责 close;</li>
 *   <li>同一 dao 横跨多个 ds 共用的并发场景属反模式,文档不推荐 —— 建议每个
 *       ds 配独立 dao bean(每 bean 各持一份 holder)。</li>
 * </ul>
 */
public class TxConnectionHolder implements ConnectionHolder {

    private volatile DataSource dataSource;

    @Override
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * 由 {@code JdbcBaseDao.setDataSource(ds)} 透传过来。语义说明:
     * <ul>
     *   <li>字段是 {@code volatile},跨线程可见,但 check-then-set 复合操作非原子;</li>
     *   <li>edap 容器下此方法一般在 bean 构造期(init)调用,之后无并发写入 —— 单写多读;</li>
     *   <li>本 holder 已不缓存 con,故无需再清任何字段;</li>
     *   <li>若业务方在运行时(并发访问 dao 期间)切换 ds,应自行保证 happens-before
     *       (例如把 dao 设为 request-scoped,或加外部锁)。</li>
     * </ul>
     */
    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 取连接 —— 优先从当前线程事务拿,fallback 到直连 ds(不缓存)。
     *
     * <p><b>tx 路径</b>:事务判定只看 {@link TransactionStatus#getResource()},
     * 不依赖显式 {@code bindResource(ds, ...)} 调用 —— Phase 2 默认
     * {@code DefaultEdapTransactionManager} 已通过 {@code bindStatus(status)}
     * 把当前事务绑到 ThreadLocal,这里直接读 status 即可拿到
     * {@link JdbcTransactionResource}。</p>
     *
     * <p><b>Plan A 自动同步 ds</b>:若 tx 资源的 ds 与本 holder 的
     * {@link #dataSource} 不一致,同步 holder.dataSource = txDs。这样 tx 结束后
     * 业务方调非事务 dao 方法时,fallback 会自然 fall 到 tx 用过的 ds,无需
     * 业务方手动 {@code setDataSource}。</p>
     *
     * <p><b>非事务 fallback</b>:直连 ds.getConnection() 拿一条新连接返回 —
     * <b>不缓存</b>。连接生命周期由调用方(典型为 {@code SingleStatementSession}
     * 在 {@code close(boolean)} 里)负责 setAutoCommit(true) + close()。
     * 这样每个 dao 方法结束都关闭自己的 con,避免跨线程共享导致"一线程 close
     * 另一线程还在用的 con → 下一次操作报 PG 这个 statement 已经被关闭"。</p>
     */
    public Connection getConnection() throws SQLException {
        TransactionStatus status = TxScope.currentStatus();
        if (status != null && status.resource() instanceof JdbcTransactionResource) {
            JdbcTransactionResource res = (JdbcTransactionResource) status.resource();
            DataSource txDs = res.getDataSource();
            // Plan A:auto-sync holder.dataSource 到 tx 用到的 ds
            if (txDs != null && txDs != this.dataSource) {
                synchronized (this) {
                    if (txDs != this.dataSource) {
                        this.dataSource = txDs;
                    }
                }
            }
            return res.getConnection();
        }
        // 非事务路径:不缓存,每次从 ds 拿新 —— 调用方负责 close
        return dataSource.getConnection();
    }

    /**
     * 外部显式注入连接(如 dao.setConnection(con))—— 本 holder 不再缓存 con,
     * 此方法保留为 no-op 以兼容 {@link ConnectionHolder} 接口契约。注入的
     * 连接应由调用方(典型为 {@code SingleStatementSession})自己持有并在
     * 适当时机关闭。
     */
    public void setConnection(Connection con) {
        // no-op:本 holder 不缓存 con,无法提供"注入后下次 getConnection 返回它"的语义
    }

    /**
     * 释放连接 —— 本 holder 不缓存 con,无法 release。{@code SingleStatementSession}
     * 在 {@code close(boolean)} 里直接关闭它持有的 con,不依赖本方法。
     *
     * <p>保留为 no-op 是为了不破坏 {@link ConnectionHolder} 接口契约;若业务方
     * 走非 SPI 路径使用 {@code SimpleConnectionHolder},本方法仍是其正常的
     * 释放入口。</p>
     */
    @Override
    public void releaseConnection() throws SQLException {
        // no-op:本 holder 不持有任何 con
    }
}