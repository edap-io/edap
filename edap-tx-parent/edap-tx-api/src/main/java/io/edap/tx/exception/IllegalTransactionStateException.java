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
 * 事务状态非法 —— 表示调用方使用了与当前事务状态不一致的语义。
 *
 * <p>典型场景:</p>
 * <ul>
 *   <li>MANDATORY 传播模型但当前无事务</li>
 *   <li>NEVER 传播模型但当前有事务</li>
 *   <li>已 {@link io.edap.tx.TransactionStatus#markCompleted()} 的事务再次 commit / rollback</li>
 *   <li>对非事务 status 调 commit / rollback</li>
 * </ul>
 *
 * <p>与 {@link TransactionSystemException} 区别:本类是 <b>业务用法错误</b>,
 * 不是基础设施失败 —— 应在开发期就被发现。</p>
 */
public class IllegalTransactionStateException extends TransactionException {

    private static final long serialVersionUID = 1L;

    public IllegalTransactionStateException(String message) {
        super(message);
    }

    public IllegalTransactionStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
