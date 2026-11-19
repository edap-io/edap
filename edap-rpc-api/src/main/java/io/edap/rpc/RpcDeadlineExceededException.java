/*
 * Copyright 2020 The edap Project
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

package io.edap.rpc;

/**
 * 业务侧主动抛出的「超时」异常 —— 框架会把它映射成 gRPC / eRPC 的
 * {@code DEADLINE_EXCEEDED}（而不是笼统的 UNKNOWN / INTERNAL）。
 *
 * <p>典型用法：业务发现「按当前上下文算，这个调用已经来不及了」，主动放弃。</p>
 *
 * <pre>{@code
 *   if (RpcContext.current().remainingMillis() < 需要的最短耗时) {
 *       throw new RpcDeadlineExceededException("剩余时间不足以完成本次请求");
 *   }
 * }</pre>
 */
public class RpcDeadlineExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RpcDeadlineExceededException(String message) {
        super(message);
    }

    public RpcDeadlineExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
