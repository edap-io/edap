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

package io.edap.erpc.codec.protobuf;

import io.edap.erpc.codec.ErpcWriter;
import io.edap.protobuf.ProtoBufWriter;

/**
 * ★ 把 protobuf 的 {@link ProtoBufWriter} 适配成 eRPC 的 {@link ErpcWriter}。
 *
 * <p>★ <b>零拷贝的关键</b>：protobuf 的 {@code ProtoBufWriter} 本来就有一块
 * 堆内缓冲（{@link ProtoBufWriter#buffer()}），编码直接写在里面 ⇒
 * 这里只是<b>把它原样交出去</b>，不产生 {@code toByteArray()} 那次整块拷贝。</p>
 *
 * <p>★ <b>借来的缓冲</b>：{@code ProtoBufWriter} 是线程本地的
 * （{@code ProtoBuf.THREAD_WRITER}），下一次编码会 {@code reset()} 并覆盖 ⇒
 * 调用方必须立即拷走，不得持有。</p>
 */
final class ProtoBufErpcWriter implements ErpcWriter {

    private final ProtoBufWriter writer;

    ProtoBufErpcWriter(ProtoBufWriter writer) {
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
