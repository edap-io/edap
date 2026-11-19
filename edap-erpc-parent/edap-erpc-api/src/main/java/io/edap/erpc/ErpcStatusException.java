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

package io.edap.erpc;

/**
 * 带 eRPC 状态码的异常 —— 服务端拦截器用它<b>拒绝</b>一次调用。
 *
 * <p>用法：</p>
 * <pre>{@code
 *   public void beforeCall(ErpcRequest request) {
 *       if (request.getContext() == null) {
 *           throw new ErpcStatusException(ErpcStatus.UNAUTHENTICATED, "缺少上下文");
 *       }
 *   }
 * }</pre>
 *
 * <p>框架收到它 ⇒ 业务 handler 不执行 ⇒ 回一个 ERROR 帧，
 * 载荷里的 {@code status} 就是这里指定的状态码。</p>
 *
 * <p>★ <b>为什么不用 {@link RpcException}</b>：那个类是<b>线上传输的 DTO</b>
 * （{@code @ProtoField} 标注的业务异常载体），不是 {@link Throwable} ⇒ 抛不出去。</p>
 */
public class ErpcStatusException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErpcStatus status;

    public ErpcStatusException(ErpcStatus status, String message) {
        super(message);
        this.status = status == null ? ErpcStatus.INTERNAL : status;
    }

    public ErpcStatusException(ErpcStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status == null ? ErpcStatus.INTERNAL : status;
    }

    /**
     * @return 本异常要回给客户端的状态码；没指定 ⇒ {@link ErpcStatus#INTERNAL}
     */
    public ErpcStatus status() {
        return status;
    }
}
