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

package io.edap.erpc;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.wire.Field;
import io.edap.erpc.enums.RpcDataType;

import java.util.List;

/**
 * 客户端发起调用时的信封 —— {@link RpcInvoker} 这条老接口的入参。
 *
 * <p>★ <b>它本身不上线</b>：eRPC 的线上格式是「32 字节帧头 + 业务 DTO 字节」
 * （见 {@link io.edap.erpc.frame.ErpcFrameHeader}）。本类是**进程内**的调用描述 ——
 * {@code request} 字段装的是业务 DTO 已经编码好的字节。</p>
 *
 * <p>★ 与 {@code io.edap.erpc.frame.ErpcRequest} 不是同一个东西：后者是服务端
 * 收到帧后交给 handler 的上下文。</p>
 */
public class RpcRequest {
    /**
     * rpc请求的类型用来区分是普通的调用，还是系统命令的调用
     */
    @ProtoField(tag = 1, type = Field.Type.ENUM)
    private RpcDataType reqType;
    /**
     * 服务在服务节点的唯一标识
     */
    @ProtoField(tag = 2, type = Field.Type.INT32)
    private int serviceId;
    /**
     * 方法在服务内的唯一标识
     */
    @ProtoField(tag = 3, type = Field.Type.INT32)
    private int methodId;
    /**
     * RPC调用的请求参数
     */
    @ProtoField(tag = 4, type = Field.Type.BYTES)
    private byte[] request;
    /**
     * RPC请求的traceId
     */
    @ProtoField(tag = 5, type = Field.Type.FIXED64)
    private long traceId;
    /**
     * 请求处理经过的节点ID的列表
     */
    @ProtoField(tag = 6, type = Field.Type.INT32, cardinality = Field.Cardinality.REPEATED)
    private List<Integer> workId;
    /**
     * 请求发起时间戳
     */
    @ProtoField(tag = 7, type = Field.Type.INT64)
    private long requestTime;
    /**
     * 异步请求时请求的唯一标识
     */
    @ProtoField(tag = 8, type = Field.Type.FIXED64)
    private long requestId;

    /**
     * 本次调用的超时（毫秒）；小于等于 0 表示用连接池的默认超时。
     *
     * <p>★ <b>不上线</b>（没有 {@code @ProtoField}）：它只是把超时从调用方
     * 带到 {@link RpcInvoker} 那条老接口上 —— 那条接口的
     * {@code invoke(RpcRequest)} 没有超时参数。</p>
     */
    private long timeoutMillis;

    /**
     * ★ RPC调用参数的<b>对象</b>（还没编码）—— 客户端延迟编码用。
     *
     * <p>为什么要有它：客户端原来在 {@code ErpcClient.invoke} 里就把请求 DTO
     * 编码成 {@code byte[]}，出站时再把这段字节拷进待写缓冲 ⇒ 载荷被拷两次。
     * 改成「客户端只交对象，出站写帧时才编码」⇒ 载荷只拷一次
     * （与 gRPC 的 {@code GrpcFrameEncoder} 同一形态）。</p>
     *
     * <p>★ <b>不上线</b>（没有 {@code @ProtoField}）：它只是进程内的调用描述。</p>
     */
    private Object requestObject;

    /**
     * rpc请求的类型
     */
    public RpcDataType getReqType() {
        return reqType;
    }

    public void setReqType(RpcDataType reqType) {
        this.reqType = reqType;
    }

    /**
     * 服务在服务节点的唯一标识
     */
    public int getServiceId() {
        return serviceId;
    }

    public void setServiceId(int serviceId) {
        this.serviceId = serviceId;
    }

    /**
     * 方法在服务内的唯一标识
     */
    public int getMethodId() {
        return methodId;
    }

    public void setMethodId(int methodId) {
        this.methodId = methodId;
    }

    /**
     * RPC调用的请求参数（业务 DTO 的编码字节）
     */
    public byte[] getRequest() {
        return request;
    }

    public void setRequest(byte[] request) {
        this.request = request;
    }

    /**
     * RPC请求的traceId
     */
    public long getTraceId() {
        return traceId;
    }

    public void setTraceId(long traceId) {
        this.traceId = traceId;
    }

    /**
     * 请求处理经过的节点ID的列表
     */
    public List<Integer> getWorkId() {
        return workId;
    }

    public void setWorkId(List<Integer> workId) {
        this.workId = workId;
    }

    /**
     * 请求发起时间戳
     */
    public long getRequestTime() {
        return requestTime;
    }

    public void setRequestTime(long requestTime) {
        this.requestTime = requestTime;
    }

    /**
     * 异步请求时请求的唯一标识
     */
    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    /**
     * 本次调用的超时（毫秒）
     */
    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * RPC调用参数的<b>对象</b>（还没编码）
     */
    public Object getRequestObject() {
        return requestObject;
    }

    /**
     * 填 RPC调用参数的<b>对象</b>（延迟到出站写帧时才编码）
     */
    public void setRequestObject(Object requestObject) {
        this.requestObject = requestObject;
    }
}
