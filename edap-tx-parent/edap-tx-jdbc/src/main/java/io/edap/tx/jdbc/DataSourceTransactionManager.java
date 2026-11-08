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

import io.edap.tx.DefaultEdapTransactionManager;
import io.edap.tx.TransactionDefinition;
import io.edap.tx.TransactionResource;
import io.edap.tx.exception.TransactionException;
import io.edap.tx.exception.TransactionSystemException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 基于 JDBC {@link DataSource} 的事务管理器 —— Phase 2 默认生产实现。
 *
 * <p><b>职责边界</b>:继承 {@link DefaultEdapTransactionManager},只重写
 * {@link #doBegin(TransactionDefinition, Object)} 钩子,把"开事务"具体化为
 * "从 DataSource 拿连接 + setAutoCommit(false) + 隔离级别透传"。</p>
 *
 * <p>决策矩阵、commit/rollback 路径、同步点回调、嵌套计数、挂起-恢复
 * 全部复用父类 —— 本类只提供 JDBC 资源工厂。</p>
 *
 * <p><b>隔离级别透传</b>:{@link io.edap.tx.isolation.Isolation#jdbcLevel()}
 * 已预制 {@code Connection.TRANSACTION_*} 映射,本类直接读 + 透传;
 * {@link io.edap.tx.isolation.Isolation#DEFAULT}(-1) 跳过
 * {@code setTransactionIsolation()} 调用,保留数据源/驱动默认级别。</p>
 *
 * <p><b>timeout 下沉到语句层</b>:JDBC 协议层没有"事务超时"这个概念,
 * 唯一能表达"这条 SQL 最多跑多久"的是 {@link java.sql.Statement#setQueryTimeout(int)}
 * —— 所以 {@code @Transactional(timeout=)} 被存进 {@link JdbcTransactionResource},
 * 由 dao 层在创建语句时套上去(见 {@code ConnectionHolder#queryTimeoutSeconds()})。
 * <b>注意语义边界</b>:它只约束 SQL 执行阶段,业务方法里 sleep/计算不计时;
 * 驱动不支持 {@code setQueryTimeout} 时会记警告并继续(见 dao 层)。</p>
 *
 * <p><b>多数据源</b>:Phase 2 单 DataSource 场景,resource key 即 DataSource 自身
 * (经由 {@link TransactionResource} 内部传递);多 DS 时由调用方在
 * {@link #getTransaction} 前管理 key 映射(Phase 4 扩展)。</p>
 */
public class DataSourceTransactionManager extends DefaultEdapTransactionManager {

    private final DataSource dataSource;

    public DataSourceTransactionManager(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * 获取本管理器绑定的 DataSource。多数据源场景下用户可传入多个 manager 实例
     * (每个绑一个 DS),或在 Phase 4 引入 manager 集合。
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    @Override
    protected TransactionResource doBegin(TransactionDefinition definition, Object resourceKey)
            throws TransactionException {
        try {
            Connection con = dataSource.getConnection();
            con.setAutoCommit(false);
            if (definition.isolation().jdbcLevel() != -1) {
                con.setTransactionIsolation(definition.isolation().jdbcLevel());
            }
            return new JdbcTransactionResource(con, dataSource, definition.timeout());
        } catch (SQLException e) {
            throw new TransactionSystemException("Failed to begin JDBC transaction", e);
        }
    }
}