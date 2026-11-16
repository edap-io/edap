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

import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecIds;
import io.edap.erpc.codec.ErpcSerializationException;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.io.BufOut;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ProtoException;

/**
 * protobuf 序列化实现 —— eRPC 的默认序列化器。
 *
 * <p>★ <b>编码走 {@code ProtoBufCodecRegister.getEncoder} 而不是
 * {@link ProtoBuf#ser(Object)}</b>：{@code ProtoBuf.ser} 走的是 Any 编码
 * （{@code [类型字节] + 载荷}），而 {@link ProtoBuf#toObject} 读的是<b>裸</b>
 * protobuf 字节流 —— 两者不对称，用 {@code ser} 编出来的字节解不开。
 * 这里的写法与 gRPC 的 {@code GrpcCodec} 完全一致（同一套编解码器 ⇒ 同一份字节）。</p>
 *
 * <p>另外，{@link ProtoBuf#toByteArray(Object)} 会把 {@link EncodeException}
 * 吞掉返回 {@code null}，调用方分不清「空对象」与「编码失败」
 * ⇒ 失败必须以异常形式冒出来。</p>
 *
 * <p>★ <b>不产出独立数组</b>：编码直接写进 {@code ProtoBuf.THREAD_WRITER}
 * 的线程本地缓冲，把这块缓冲<b>借</b>出去 ⇒ 载荷在网络上只被拷一次
 * （编到出站缓冲那一次），省掉 {@code toByteArray()} 的整块拷贝。</p>
 */
public class ProtobufErpcCodec implements ErpcCodec {

    @Override
    public int id() {
        return ErpcCodecIds.PROTOBUF;
    }

    @Override
    public String name() {
        return "protobuf";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ErpcWriter encode(Object obj) {
        if (obj == null) {
            return null;
        }
        ProtoBufWriter writer = ProtoBuf.THREAD_WRITER.get();
        writer.reset();
        BufOut out = writer.getBufOut();
        out.reset();
        try {
            ProtoBufEncoder encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(obj.getClass());
            encoder.encode(writer, obj);
            // ★ writer.buffer() 必须在编码之后取：expand 扩容时会换一块更大的数组
            return new ProtoBufErpcWriter(writer);
        } catch (EncodeException e) {
            throw new ErpcSerializationException("protobuf 编码失败: " + obj.getClass().getName(), e);
        } finally {
            // 只重置写指针，不清内容 —— 借出去的缓冲在返回后仍然可读
            out.reset();
        }
    }

    @Override
    public <T> T decode(byte[] data, int offset, int len, Class<T> type) {
        if (data == null) {
            return null;
        }
        try {
            return ProtoBuf.toObject(data, offset, len, type);
        } catch (ProtoException e) {
            throw new ErpcSerializationException("protobuf 解码失败: " + type.getName(), e);
        }
    }
}
