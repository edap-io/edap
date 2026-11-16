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

/**
 * eRPC 的调用状态码。
 *
 * <p>0~16 与 gRPC 规范（{@code https://grpc.github.io/grpc/core/md_doc_statuscodes.html}）
 * 逐值对齐，17 起是 eRPC 专有的两个状态。</p>
 *
 * <p>状态码随 ERROR 帧的载荷（{@link RpcException}）一起发给客户端。</p>
 */
public enum ErpcStatus {

    OK(0),
    CANCELLED(1),
    UNKNOWN(2),
    INVALID_ARGUMENT(3),
    DEADLINE_EXCEEDED(4),
    NOT_FOUND(5),
    ALREADY_EXISTS(6),
    PERMISSION_DENIED(7),
    RESOURCE_EXHAUSTED(8),
    FAILED_PRECONDITION(9),
    ABORTED(10),
    OUT_OF_RANGE(11),
    UNIMPLEMENTED(12),
    INTERNAL(13),
    UNAVAILABLE(14),
    DATA_LOSS(15),
    UNAUTHENTICATED(16),
    /** 服务端不认识这个 methodId。 */
    UNKNOWN_METHOD(17),
    /** 服务端不认识这个帧头里的 codec id。 */
    UNKNOWN_CODEC(18);

    private final int code;

    ErpcStatus(int code) {
        this.code = code;
    }

    /**
     * @return 状态码数值
     */
    public int code() {
        return code;
    }

    /**
     * 由状态码数值取状态枚举。
     *
     * @param code 状态码数值
     * @return 状态枚举；未知的状态码返回 {@link #UNKNOWN}
     */
    public static ErpcStatus fromCode(int code) {
        for (ErpcStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return UNKNOWN;
    }
}
