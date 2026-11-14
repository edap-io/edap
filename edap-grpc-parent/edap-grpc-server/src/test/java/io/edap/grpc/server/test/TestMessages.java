/*
 * Copyright 2020 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.grpc.server.test;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.wire.Field;

/**
 * 测试用的 protobuf 消息。
 */
public class TestMessages {

    /**
     * 请求消息。
     */
    public static class Req {
        @ProtoField(tag = 1, type = Field.Type.STRING)
        public String name;

        @ProtoField(tag = 2, type = Field.Type.INT32)
        public int value;
    }

    /**
     * 响应消息。
     */
    public static class Resp {
        @ProtoField(tag = 1, type = Field.Type.STRING)
        public String name;

        @ProtoField(tag = 2, type = Field.Type.INT32)
        public int value;
    }
}
