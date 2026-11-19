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
 * 一次 eRPC 调用的应答信封 —— <b>客户端与服务端共用同一个类</b>。
 *
 * <p>★ <b>它不是线格式</b>：线上格式是「32 字节帧头 + 业务 DTO 字节」
 * （见 {@link io.edap.erpc.frame.ErpcFrameHeader}）。本类是<b>进程内</b>的应答上下文。</p>
 *
 * <p>★ <b>两侧各用各的那一半字段</b>：</p>
 * <table border="1">
 *   <caption>字段分工</caption>
 *   <tr><th>字段</th><th>客户端（收方）</th><th>服务端（发方）</th></tr>
 *   <tr><td>{@code methodId} / {@code serviceId} / {@code requestId} / {@code traceId}</td>
 *       <td>从响应帧解出来</td><td>从请求帧原样回填</td></tr>
 *   <tr><td>{@code codec}</td>
 *       <td>响应帧的 codec 字节</td><td>原样抄回请求帧的 codec</td></tr>
 *   <tr><td>{@code payload}</td>
 *       <td>业务 DTO 的编码字节</td><td>handler 填（编码字节）</td></tr>
 *   <tr><td>{@code result}</td>
 *       <td>不用</td><td>handler 填业务 DTO <b>对象</b>（框架写帧时才编码）</td></tr>
 *   <tr><td>{@code exception}</td>
 *       <td>服务端回的 ERROR 帧里的错误体</td><td>业务异常（非空 ⇒ 框架发 ERROR 帧）</td></tr>
 *   <tr><td>{@code success}</td>
 *       <td>这次调用成功吗</td><td>不用</td></tr>
 * </table>
 *
 * <p>★ <b>{@code payload} 与 {@code result} 的关系</b>（服务端侧）：两者都填时
 * 以 {@code result} 为准（{@code result} 为 {@code null} 才回退到 {@code payload}）。</p>
 */
public class ErpcResponse {

    /** 帧头里的 codec 字节（序列化器 id）—— 服务端会原样抄回请求帧的 codec。 */
    private int codec;
    /** 帧头里的 methodId —— 从请求帧原样回填。 */
    private int methodId;
    /** 帧头里的 serviceId —— 从请求帧原样回填。 */
    private int serviceId;
    /** 帧头里的 requestId —— 从请求帧原样回填（客户端据此配对）。 */
    private int requestId;
    /** 帧头里的 traceId —— 从请求帧原样回填。 */
    private long traceId;
    /** 业务 DTO 的编码字节（未编码时由 handler 填）。 */
    private byte[] payload;
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
    private Object result;
    /** 业务异常 —— 非空时框架发 ERROR 帧而不是 RESPONSE 帧。 */
    private RpcException exception;
    /**
     * 这次调用成功吗 —— <b>客户端侧</b>用。
     *
     * <p>★ 服务端侧不填（服务端用 {@link #exception} 表达失败）。</p>
     */
    private boolean success;

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

    /**
     * 请求号（连接内唯一）—— 从请求帧原样回填。
     */
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

    /**
     * 业务 DTO 的编码字节。
     */
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

    /**
     * 这次调用成功吗（客户端侧）。
     */
    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    @Override
    public String toString() {
        return "ErpcResponse{methodId=" + methodId + ", requestId=" + requestId
            + ", codec=" + codec + ", payload="
            + (payload == null ? 0 : payload.length) + "B"
            + (exception == null ? "" : ", exception=" + exception.getMessage()) + "}";
    }
}
