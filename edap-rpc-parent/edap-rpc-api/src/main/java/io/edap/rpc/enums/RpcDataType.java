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

package io.edap.rpc.enums;

public enum RpcDataType {

    /**
     * 普通的远程调用的请求
     */
    INVOKE_REQ(0),
    /**
     * 普通的远程调用的返回
     */
    INVOKE_RESP(1),
    /**
     * meta信息请求
     */
    META_REQ(2),
    /**
     * meta请求返回
     */
    META_RESP(3);

    private int value;

    RpcDataType(int v) {
        this.value = v;
    }

    public int getValue() {
        return this.value;
    }

    public static RpcDataType valueOf(int value) {
        switch (value) {
            case 1:
                return INVOKE_RESP;
            case 2:
                return META_REQ;
            case 3:
                return META_RESP;
            default:
                return INVOKE_REQ;
        }
    }
}
