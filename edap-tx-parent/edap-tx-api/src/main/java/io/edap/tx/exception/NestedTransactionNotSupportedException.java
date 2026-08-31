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

package io.edap.tx.exception;

/**
 * NESTED 传播模型请求,但底层资源不支持 savepoint。
 *
 * <p>触发场景:</p>
 * <ul>
 *   <li>{@link io.edap.tx.TransactionResource#createSavepoint()} 在不支持 savepoint 的
 *       资源(JTA-only / 内存 mock)上调用</li>
 *   <li>Phase 4 的 XA 资源不支持 savepoint(XA 规范的"嵌套"由协调器层处理,
 *       不依赖数据库 savepoint)</li>
 * </ul>
 *
 * <p>调用方应:换用 {@link io.edap.tx.propagation.Propagation#REQUIRED} 改写嵌套语义,
 * 或换支持 savepoint 的资源(如本地 JDBC Connection)。</p>
 */
public class NestedTransactionNotSupportedException extends TransactionException {

    private static final long serialVersionUID = 1L;

    public NestedTransactionNotSupportedException(String message) {
        super(message);
    }

    public NestedTransactionNotSupportedException(String message, Throwable cause) {
        super(message, cause);
    }
}
