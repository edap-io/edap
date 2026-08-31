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
 * 事务子系统异常根。所有 tx 相关的异常都继承自本类。
 *
 * <p><b>为什么 extends RuntimeException</b>:对齐 edap 容器异常风格
 * (见 {@code feedback_edap_exc_runtime.md}),不污染
 * {@link io.edap.container.BeanContainer} / {@link io.edap.container.AppContext}
 * 等核心方法签名。</p>
 */
public class TransactionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TransactionException(String message) {
        super(message);
    }

    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }

    public TransactionException(Throwable cause) {
        super(cause);
    }
}
