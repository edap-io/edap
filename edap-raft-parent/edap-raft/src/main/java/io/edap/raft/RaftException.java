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

package io.edap.raft;

/**
 * Raft 运行时异常。
 *
 * <p>沿用 edap 的约定：容器/框架层的异常一律是 RuntimeException 的子类，
 * 不污染调用方的方法签名。</p>
 */
public class RaftException extends RuntimeException {

    public RaftException(String message) {
        super(message);
    }

    public RaftException(String message, Throwable cause) {
        super(message, cause);
    }
}
