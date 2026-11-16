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

import io.edap.erpc.codec.ErpcCodec;

/**
 * 服务端侧的一次请求帧上下文。
 *
 * <p>★ <b>与 {@code io.edap.erpc.RpcRequest} 不是同一个东西</b>：</p>
 * <ul>
 *   <li>{@code io.edap.erpc.frame.ErpcRequest}（本类）：**服务端**收到一个帧后，
 *       交给 {@link io.edap.erpc.ErpcHandler} 用的上下文。字段与线上帧头一一对应，
 *       {@code payload} 是**还没解码**的业务 DTO 字节。</li>
 *   <li>{@code io.edap.erpc.RpcRequest}：**客户端**发起调用时用的信封，
 *       走 {@link io.edap.erpc.RpcInvoker} 这条老接口。它本身不上线 ——
 *       上线的是帧头 + 业务 DTO。</li>
 * </ul>
 *
 * <p>载荷在这里保持「未解码」是有意的：解码需要业务 DTO 的 {@code Class}，
 * 只有 {@link io.edap.erpc.ErpcMethod} 知道 ⇒ 解码发生在 handler 里。</p>
 */
public class ErpcRequest {

    /** 帧头里的 type 字节（{@link ErpcFrameType#getValue()}）。 */
    private int    type;
    /** 帧头里的 codec 字节（序列化器 id）。 */
    private int    codec;
    /** 帧头里的 methodId —— 服务端靠它路由，不解析载荷。 */
    private int    methodId;
    /** 帧头里的 serviceId（观测用）。 */
    private int    serviceId;
    /** 帧头里的 requestId —— 响应帧原样回填。 */
    private int    requestId;
    /** 帧头里的 traceId。 */
    private long   traceId;
    /** 业务 DTO 的编码字节（未解码）。 */
    private byte[] payload;
    /**
     * 本帧载荷的编解码器 —— 服务端按帧头 {@code codec} 字节现场解析出来。
     *
     * <p>★ <b>不上线</b>：它不是客户端发来的字段，而是服务端根据帧头解析的结果。
     * handler 靠它解码请求载荷、编码响应载荷。</p>
     */
    private transient ErpcCodec serializer;

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

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
     * 本帧载荷的序列化器（服务端按帧头 codec 字节解析出来的）。
     */
    public ErpcCodec getSerializer() {
        return serializer;
    }

    public void setSerializer(ErpcCodec serializer) {
        this.serializer = serializer;
    }

    @Override
    public String toString() {
        return "ErpcRequest{methodId=" + methodId + ", requestId=" + requestId
            + ", codec=" + codec + ", traceId=" + traceId
            + ", payload=" + (payload == null ? 0 : payload.length) + "B}";
    }
}
