/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.grpc;

/**
 * 带 gRPC 状态码的异常 —— 服务端拦截器用它<b>拒绝</b>一次调用。
 *
 * <p>用法：</p>
 * <pre>{@code
 *   public void beforeCall(GrpcCall call) {
 *       if (call.metadata().get("authorization") == null) {
 *           throw new GrpcStatusException(GrpcStatus.UNAUTHENTICATED, "缺少 authorization");
 *       }
 *   }
 * }</pre>
 *
 * <p>框架收到它 ⇒ 业务 handler 不执行 ⇒ trailers 里回 {@code grpc-status=16}。</p>
 */
public class GrpcStatusException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final GrpcStatus status;

    public GrpcStatusException(GrpcStatus status, String message) {
        super(message);
        this.status = status == null ? GrpcStatus.INTERNAL : status;
    }

    public GrpcStatusException(GrpcStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status == null ? GrpcStatus.INTERNAL : status;
    }

    /**
     * @return 本异常要回给客户端的状态码；没指定 ⇒ {@link GrpcStatus#INTERNAL}
     */
    public GrpcStatus status() {
        return status;
    }
}
