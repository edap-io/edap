/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.erpc.frame;

import io.edap.erpc.RpcException;

/**
 * 服务端侧的一次响应帧上下文 —— {@link io.edap.erpc.ErpcHandler} 往里填结果。
 *
 * <p>★ <b>与 {@code io.edap.erpc.RpcResponse} 不是同一个东西</b>：</p>
 * <ul>
 *   <li>{@code io.edap.erpc.frame.ErpcResponse}（本类）：**服务端**回帧时用的上下文，
 *       字段与线上帧头一一对应。</li>
 *   <li>{@code io.edap.erpc.RpcResponse}：**客户端**收到响应后用的信封，
 *       走 {@link io.edap.erpc.RpcInvoker} 这条老接口。</li>
 * </ul>
 *
 * <p>handler 填 {@code payload}（业务 DTO 的编码字节）⇒ 框架发 RESPONSE 帧；
 * 填 {@code exception}（或让 handle 抛异常）⇒ 框架发 ERROR 帧，
 * 载荷是编码后的 {@link RpcException}。</p>
 */
public class ErpcResponse {

    /** 帧头里的 codec 字节（序列化器 id）—— 服务端会原样抄回请求帧的 codec。 */
    private int          codec;
    /** 帧头里的 methodId —— 从请求帧原样回填。 */
    private int          methodId;
    /** 帧头里的 serviceId —— 从请求帧原样回填。 */
    private int          serviceId;
    /** 帧头里的 requestId —— 从请求帧原样回填（客户端据此配对）。 */
    private int          requestId;
    /** 帧头里的 traceId —— 从请求帧原样回填。 */
    private long         traceId;
    /** 业务 DTO 的编码字节（未编码时由 handler 填）。 */
    private byte[]       payload;
    /**
     * ★ 业务 DTO <b>对象</b>（还没编码）—— handler 填这个，框架在<b>写帧时才编码</b>。
     *
     * <p>为什么要有它：handler 里编码一次，框架写帧时又要整块拷一次
     * ⇒ 载荷在网络上被拷两次。改成「handler 只交对象，框架写帧时才编码」，
     * 载荷就<b>只拷一次</b>（与 gRPC 的 {@code GrpcCallImpl.sendOne} 同一形态）。</p>
     *
     * <p>★ 与 {@link #payload} 的关系：两者都填时以 {@code result} 为准
     * （{@code result} 为 {@code null} 才回退到 {@code payload}）。</p>
     */
    private Object       result;
    /** 业务异常 —— 非空时框架发 ERROR 帧而不是 RESPONSE 帧。 */
    private RpcException exception;

    public int getCodec() {
        return codec;
    }

    public void setCodec(int codec) {
        this.codec = codec;
    }

    public int getMethodId() {
        return methodId;
    }

    public void setMethodId(int methodId) {
        this.methodId = methodId;
    }

    public int getServiceId() {
        return serviceId;
    }

    public void setServiceId(int serviceId) {
        this.serviceId = serviceId;
    }

    public int getRequestId() {
        return requestId;
    }

    public void setRequestId(int requestId) {
        this.requestId = requestId;
    }

    public long getTraceId() {
        return traceId;
    }

    public void setTraceId(long traceId) {
        this.traceId = traceId;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    /**
     * 取业务 DTO <b>对象</b>（框架回帧时才编码）。
     */
    public Object getResult() {
        return result;
    }

    /**
     * 填业务 DTO <b>对象</b> —— 框架在写帧时才编码（省一次整块拷贝）。
     */
    public void setResult(Object result) {
        this.result = result;
    }

    public RpcException getException() {
        return exception;
    }

    public void setException(RpcException exception) {
        this.exception = exception;
    }

    @Override
    public String toString() {
        return "ErpcResponse{methodId=" + methodId + ", requestId=" + requestId
            + ", codec=" + codec + ", payload="
            + (payload == null ? 0 : payload.length) + "B"
            + (exception == null ? "" : ", exception=" + exception.getMessage()) + "}";
    }
}
