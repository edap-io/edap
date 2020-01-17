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

package io.edap.protobuf;

import io.edap.io.BufOut;
import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.internal.ProtoBufOut;
import io.edap.protobuf.reader.ByteArrayReader;
import io.edap.protobuf.writer.StandardProtoBufWriter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ProtoBuf {

    enum EncodeType {
        STANDARD,
        FAST;
    }

    public static class ProtoFieldInfo {
        public Field field;
        public Method method;
        public Method setMethod;
        public ProtoField protoField;
    }

    private ProtoBuf() {}

    private static final ProtoBufCodecRegister REGISTER =
            ProtoBufCodecRegister.INSTANCE;

    /**
     * 本地线程的ProtoBuf的Writer减少内存分配次数
     */
    private static final ThreadLocal<ProtoBufWriter> THREAD_WRITER;

    static {
        THREAD_WRITER = ThreadLocal.withInitial(() -> {
            BufOut out    = new ProtoBufOut();
            ProtoBufWriter writer = new StandardProtoBufWriter(out);
            return writer;
        });
    }

    /**
     * 将java对象序列化为Protobuf的字节数组
     * @param obj 需要序列化的java对象
     * @return 返回序列化后的字节数组，如果对象为null则返回null
     */
    public static byte [] toByteArray(Object obj) {
        if (obj == null) {
            return null;
        }
        ProtoBufEncoder codec = REGISTER.getEncoder(obj.getClass());
        ProtoBufWriter writer = THREAD_WRITER.get();
        writer.reset();
        BufOut out = writer.getBufOut();
        out.reset();

        byte[] bs;
        try {
            codec.encode(writer, obj);
            int len = writer.getPos();
            bs = new byte[len];
            System.arraycopy(out.getWriteBuf().bs, 0, bs, 0, len);
            out.reset();
        } finally {
            THREAD_WRITER.set(writer);
        }
        return bs;
    }

    /**
     * 将protobuf的字节数组，反序列化为对象，如果protobuf字节数组和给定的java类的数据类型不一致
     * 则报错
     * @param <T> 对象类型
     * @param bs 自己数组
     * @param cls 给定的java对象的class
     * @return 返回java对象
     * @throws ProtoBufException
     */
    public static <T> T toObject(byte [] bs, Class<T> cls) throws ProtoBufException {
        return toObject(bs, 0, bs.length, cls);
    }

    /**
     * 将protobuf的字节数组，反序列化为对象，如果protobuf字节数组和给定的java类的数据类型不一致
     * 则报错
     * @param <T> 对象类型
     * @param bs 自己数组
     * @param offset 数组开始的下标
     * @param len protobuf数据的长度
     * @param cls 给定的java对象的class
     * @return 返回java对象
     * @throws ProtoBufException
     */
    public static <T> T toObject(byte [] bs, int offset, int len, Class<T> cls)
            throws ProtoBufException {
        ByteArrayReader reader = new ByteArrayReader(bs, offset, len);
        ProtoBufDecoder codec = REGISTER.getDecoder(cls);
        return (T)codec.decode(reader);
    }
}