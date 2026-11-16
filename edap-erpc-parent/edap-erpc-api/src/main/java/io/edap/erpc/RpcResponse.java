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
 * 客户端收到响应后的信封 —— {@link RpcInvoker} 这条老接口的返回值。
 *
 * <p>★ <b>它本身不上线</b>：eRPC 的线上格式是「32 字节帧头 + 业务 DTO 字节」
 * （见 {@link io.edap.erpc.frame.ErpcFrameHeader}）。本类是**进程内**的调用结果 ——
 * {@code response} 字段装的是业务 DTO 已经编码好的字节。</p>
 *
 * <p>★ 与 {@code io.edap.erpc.frame.ErpcResponse} 不是同一个东西：后者是服务端
 * 回帧时用的上下文。</p>
 */
public class RpcResponse {
    /**
     * rpc请求的类型用来区分是普通的调用响应，还是系统命令的调用响应
     */
    @ProtoField(tag = 1, type = Field.Type.ENUM)
    private RpcDataType respType;
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
     * 远程调用的返回对象protobuf序列化后的二进制数据
     */
    @ProtoField(tag = 4, type = Field.Type.BYTES)
    private byte[] response;
    /**
     * 处理响应时间戳
     */
    @ProtoField(tag = 5, type = Field.Type.INT64)
    private long respTime;
    /**
     * traceId
     */
    @ProtoField(tag = 6, type = Field.Type.FIXED64)
    private long traceId;
    /**
     * 异步请求时请求的唯一标识
     */
    @ProtoField(tag = 7, type = Field.Type.FIXED64)
    private long requestId;
    /**
     * 请求处理经过的节点ID的列表
     */
    @ProtoField(tag = 8, type = Field.Type.INT32, cardinality = Field.Cardinality.REPEATED)
    private List<Integer> workId;
    /**
     * 调用是否正常
     */
    @ProtoField(tag = 9, type = Field.Type.BOOL)
    private boolean success;
    /**
     * 调用产生的异常信息
     */
    @ProtoField(tag = 10, type = Field.Type.MESSAGE)
    private RpcException exception;

    /**
     * rpc响应的类型
     */
    public RpcDataType getRespType() {
        return respType;
    }

    public void setRespType(RpcDataType respType) {
        this.respType = respType;
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
     * 远程调用的返回对象编码后的二进制数据
     */
    public byte[] getResponse() {
        return response;
    }

    public void setResponse(byte[] response) {
        this.response = response;
    }

    /**
     * 处理响应时间戳
     */
    public long getRespTime() {
        return respTime;
    }

    public void setRespTime(long respTime) {
        this.respTime = respTime;
    }

    /**
     * traceId
     */
    public long getTraceId() {
        return traceId;
    }

    public void setTraceId(long traceId) {
        this.traceId = traceId;
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
     * 请求处理经过的节点ID的列表
     */
    public List<Integer> getWorkId() {
        return workId;
    }

    public void setWorkId(List<Integer> workId) {
        this.workId = workId;
    }

    /**
     * 调用是否正常
     */
    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    /**
     * 调用产生的异常信息
     */
    public RpcException getException() {
        return exception;
    }

    public void setException(RpcException exception) {
        this.exception = exception;
    }
}
