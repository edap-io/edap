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

package io.edap.tx.isolation;

import java.sql.Connection;

/**
 * 事务隔离级别。第一期只声明枚举,不实现 JDBC {@code setTransactionIsolation()}——
 * 后续按需启用,默认走底层数据源/驱动默认级别(见 TX_DESIGN.md §2.2)。
 *
 * <p><b>重要约束</b>:XA 分布式事务路径下强制 {@link #READ_COMMITTED}——
 * PG 不支持 REPEATABLE_READ 下的 XA PREPARE。{@code DataSourceTransactionManager}
 * 检测到 XA 路径 + 非 RC 时应降级 + WARN 日志。</p>
 */
public enum Isolation {

    /**
     * 用底层数据源/驱动默认级别。JDBC 值为 -1 表示不显式设置。
     */
    DEFAULT(-1),

    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),

    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),

    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),

    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

    private final int jdbcLevel;

    Isolation(int jdbcLevel) {
        this.jdbcLevel = jdbcLevel;
    }

    /**
     * 对应的 JDBC {@code Connection} 常量值。{@code DEFAULT} 返回 -1,
     * 调用方应跳过 {@code setTransactionIsolation()} 调用。
     */
    public int jdbcLevel() {
        return jdbcLevel;
    }
}
