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

import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecIds;
import io.edap.erpc.codec.ErpcSerializationException;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.json.Eson;
import io.edap.json.JsonParseException;
import io.edap.json.JsonWriter;

/**
 * JSON 序列化实现 —— eRPC 的第二个序列化器。
 *
 * <p>存在的意义是**证明序列化是可插拔的**：同一套 eRPC 协议、同一份业务 DTO，
 * 把配置从 {@code protobuf} 改成 {@code json} 就换了线上格式，客户端与服务端
 * 各自独立配置、按帧头的 codec 字节协商。</p>
 *
 * <p>★ 调试友好（能直接用 curl/文本工具看载荷），但比 protobuf 大得多 ——
 * 只适合调试接口或对体积不敏感的场景。</p>
 *
 * <p>★ <b>不产出独立数组</b>：走 {@code Eson.THREAD_WRITER} 把对象写进
 * 线程本地的 json 缓冲，再把那块缓冲<b>借</b>出去 ⇒ 省掉
 * {@code Eson.toJsonBytes} 内部的 {@code toByteArray()} 整块拷贝
 * （json 的载荷拷贝次数因此从 2 次降到 1 次）。</p>
 */
public class JsonErpcCodec implements ErpcCodec {

    @Override
    public int id() {
        return ErpcCodecIds.JSON;
    }

    @Override
    public String name() {
        return "json";
    }

    @Override
    public ErpcWriter encode(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            JsonWriter writer = Eson.THREAD_WRITER.get();
            // ★ 与 Eson.toJsonBytes 一致：把 writer 的 feature 复位成默认值，
            //   否则同线程上别的调用（Eson.toJsonString 带 feature）会把
            //   LONG_TO_STRING 之类的开关留在这里 ⇒ 字节与 toJsonBytes 不一致。
            if (writer.getFeatureValue() != 0) {
                writer.setFeatureValue(0);
            }
            writer.reset();
            Eson.serialize(obj, writer);
            return new JsonErpcWriter(writer);
        } catch (RuntimeException e) {
            throw new ErpcSerializationException("json 编码失败: " + obj.getClass().getName(), e);
        }
    }

    @Override
    public <T> T decode(byte[] data, int offset, int len, Class<T> type) {
        if (data == null) {
            return null;
        }
        try {
            // Eson 的解析入口只接受整个数组 ⇒ 非整段时先切一刀
            if (offset == 0 && len == data.length) {
                return Eson.parseObject(data, type);
            }
            byte[] slice = new byte[len];
            System.arraycopy(data, offset, slice, 0, len);
            return Eson.parseObject(slice, type);
        } catch (JsonParseException e) {
            throw new ErpcSerializationException("json 解码失败: " + type.getName(), e);
        }
    }
}
