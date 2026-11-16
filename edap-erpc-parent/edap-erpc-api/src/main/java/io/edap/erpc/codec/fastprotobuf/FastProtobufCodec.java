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

import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecIds;
import io.edap.erpc.codec.ErpcSerializationException;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.io.BufOut;
import io.edap.protobuf.CodecType;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.internal.ProtoBufOut;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.writer.FastProtoBufWriter;

/**
 * ★ 改进版 protobuf（FAST）序列化实现 —— 优化 String 与内嵌消息的编码算法。
 *
 * <p><b>与标准 protobuf 的关系</b>：FAST 是<b>另一种线上格式</b>
 * （字符串用 UTF-16 内部字节写、长度前缀的写法也不同）⇒ 它有自己的
 * {@link ErpcCodecIds#FAST_PROTOBUF} id，两端必须都用它才解得动。
 * 帧头里带着 codec ⇒ 服务端按帧协商，不需要整条连接统一。</p>
 *
 * <p>★ 与标准 protobuf 共用同一套生成好的编码器/解码器
 * （{@link ProtoBufCodecRegister#getEncoder(Class, ProtoBufOption)}），
 * 差别只在「用哪个 writer 写」+「用哪个 option 生成编码器」。</p>
 */
public class FastProtobufCodec implements ErpcCodec {

    /**
     * FAST 的编解码选项 —— {@code ProtoBuf.FAST_PROTOBUF_TYPE} 是私有的，这里自己建一份。
     */
    private static final ProtoBufOption FAST_OPTION = createFastOption();

    /**
     * FAST 的线程本地 writer —— {@code ProtoBuf.THREAD_FAST_WRITER} 也是私有的。
     */
    private static final ThreadLocal<ProtoBufWriter> FAST_WRITER =
        ThreadLocal.withInitial(() -> new FastProtoBufWriter(new ProtoBufOut()));

    private static ProtoBufOption createFastOption() {
        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(CodecType.FAST);
        return option;
    }

    @Override
    public int id() {
        return ErpcCodecIds.FAST_PROTOBUF;
    }

    @Override
    public String name() {
        return "fastprotobuf";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ErpcWriter encode(Object obj) {
        if (obj == null) {
            return null;
        }
        ProtoBufWriter writer = FAST_WRITER.get();
        writer.reset();
        BufOut out = writer.getBufOut();
        out.reset();
        try {
            ProtoBufEncoder encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(obj.getClass(), FAST_OPTION);
            encoder.encode(writer, obj);
            return new FastProtoBufErpcWriter(writer);
        } catch (EncodeException e) {
            throw new ErpcSerializationException("fastprotobuf 编码失败: " + obj.getClass().getName(), e);
        } finally {
            out.reset();
        }
    }

    @Override
    public <T> T decode(byte[] data, int offset, int len, Class<T> type) {
        if (data == null) {
            return null;
        }
        try {
            return ProtoBuf.toObject(data, offset, len, type, FAST_OPTION);
        } catch (ProtoException e) {
            throw new ErpcSerializationException("fastprotobuf 解码失败: " + type.getName(), e);
        }
    }
}
