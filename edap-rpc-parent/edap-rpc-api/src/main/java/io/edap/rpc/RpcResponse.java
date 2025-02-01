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

package io.edap.rpc;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.wire.Field;
import io.edap.rpc.enums.RpcDataType;

import java.util.List;

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


}
