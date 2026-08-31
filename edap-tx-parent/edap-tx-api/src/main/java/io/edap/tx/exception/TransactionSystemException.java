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
 * 事务基础设施失败 —— 表示底层资源(JDBC Connection / XA Resource / 协调器)
 * 出现了不可恢复的错误。
 *
 * <p>典型场景:</p>
 * <ul>
 *   <li>Connection commit / rollback 抛 SQLException</li>
 *   <li>XA PREPARE / COMMIT 抛 XAException</li>
 *   <li>edap-pg-proxy 协调器超时</li>
 *   <li>同步点 beforeCommit 抛异常(已被 commit/rollback 包裹)</li>
 * </ul>
 *
 * <p>与 {@link IllegalTransactionStateException} 区别:本类是 <b>运行时基础设施错误</b>,
 * 通常与业务代码无关,需要重试或运维介入。</p>
 */
public class TransactionSystemException extends TransactionException {

    private static final long serialVersionUID = 1L;

    public TransactionSystemException(String message) {
        super(message);
    }

    public TransactionSystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
