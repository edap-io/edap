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

package io.edap.erpc.codec.json;

import io.edap.erpc.codec.ErpcWriter;
import io.edap.json.JsonWriter;

/**
 * ★ 把 json 的 {@link JsonWriter} 适配成 eRPC 的 {@link ErpcWriter}。
 *
 * <p>★ <b>零拷贝的关键</b>：json 的 {@link JsonWriter} 用的是
 * {@code ByteArrayJsonWriter}，它有一块堆内缓冲（{@link JsonWriter#getBuf()}），
 * 序列化直接写在里面 ⇒ 这里把它原样交出去，
 * 省掉 {@code Eson.toJsonBytes} 内部的 {@code toByteArray()} 整块拷贝。</p>
 *
 * <p>★ <b>借来的缓冲</b>：{@code JsonWriter} 是线程本地的
 * （{@code Eson.THREAD_WRITER}），下一次序列化会 {@code reset()} 并覆盖 ⇒
 * 调用方必须立即拷走，不得持有。</p>
 */
final class JsonErpcWriter implements ErpcWriter {

    private final JsonWriter writer;

    JsonErpcWriter(JsonWriter writer) {
        this.writer = writer;
    }

    @Override
    public int size() {
        return writer.size();
    }

    @Override
    public byte[] buffer() {
        return writer.getBuf();
    }
}
