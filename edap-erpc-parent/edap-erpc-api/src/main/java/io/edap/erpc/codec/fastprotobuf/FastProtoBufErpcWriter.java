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

package io.edap.erpc.codec.fastprotobuf;

import io.edap.erpc.codec.ErpcWriter;
import io.edap.protobuf.ProtoBufWriter;

/**
 * ★ 把 FAST protobuf 的 {@link ProtoBufWriter} 适配成 eRPC 的 {@link ErpcWriter}。
 *
 * <p>与标准 protobuf 的适配器同构，单独一个类是为了让两个实现各自独立演进
 * （FAST 的 writer 是 {@code FastProtoBufWriter}）。</p>
 */
final class FastProtoBufErpcWriter implements ErpcWriter {

    private final ProtoBufWriter writer;

    FastProtoBufErpcWriter(ProtoBufWriter writer) {
        this.writer = writer;
    }

    @Override
    public int size() {
        return writer.size();
    }

    @Override
    public byte[] buffer() {
        return writer.buffer();
    }
}
