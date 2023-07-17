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
import io.edap.protobuf.ext.AnyCodec;
import io.edap.protobuf.internal.ProtoBufOut;
import io.edap.protobuf.reader.ByteArrayReader;
import io.edap.protobuf.writer.StandardProtoBufWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ProtoBuf {

    /**
     * ProtoBuf的编解码器类型，标准的和google官方的兼容。Fast则才有优化的编解码方式。
     */
    public enum CodecType {
        STANDARD,
        FAST;
    }

    public static class ProtoFieldInfo {
        public Field field;
        /**
         * 是否有Get方法或者Field是public，可以直接获取Field的值
         */
        public boolean hasGetAccessed;
        /**
         * 是否有Set方法或者Field是public，可以直接给Field赋值
         */
        public boolean hasSetAccessed;
        public Method getMethod;
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
            int len = writer.size();
            bs = new byte[len];
            System.arraycopy(out.getWriteBuf().bs, 0, bs, 0, len);
            return bs;
        } catch (EncodeException e) {
            //throw e;
        } finally {
            THREAD_WRITER.set(writer);
        }
        return null;
    }

    public static String conver2HexStr(byte[] b) {
        if (b == null || b.length == 0) {
            return "";
        }
        StringBuffer result = new StringBuffer();
        for (int i = 0; i < b.length; i++) {
            result.append(Long.toString(b[i] & 0xff, 16) + ",");
        }
        return result.toString().substring(0, result.length() - 1);
    }

    public static byte [] ser(Object obj) throws EncodeException {
        ProtoBufWriter writer = THREAD_WRITER.get();
        writer.reset();
        BufOut out = writer.getBufOut();
        out.reset();
        byte[] bs;
        try {
            AnyCodec.encode(writer, obj);
            int len = writer.size();
            bs = new byte[len];
            System.arraycopy(out.getWriteBuf().bs, 0, bs, 0, len);
            out.reset();
            return bs;
        } catch (EncodeException e) {
            throw e;
        } finally {
            THREAD_WRITER.set(writer);
        }
    }

    public static byte [] ser(Object obj, byte[] prefixData) throws EncodeException {
        ProtoBufWriter writer = THREAD_WRITER.get();
        writer.reset();
        BufOut out = writer.getBufOut();
        out.reset();
        byte[] bs;
        try {
            writer.writeBytes(prefixData);
            AnyCodec.encode(writer, obj);
            int len = writer.size();
            bs = new byte[len];
            System.arraycopy(out.getWriteBuf().bs, 0, bs, 0, len);
            out.reset();
            return bs;
        } catch (EncodeException e) {
            throw e;
        } finally {
            THREAD_WRITER.set(writer);
        }
    }

    public static void ser(OutputStream output, Object obj) throws EncodeException {
        ProtoBufWriter writer = THREAD_WRITER.get();
        writer.reset();
        BufOut out = writer.getBufOut();
        out.reset();
        byte[] bs;
        try {
            AnyCodec.encode(writer, obj);
            int len = writer.size();

            writer.toStream(output);
            out.reset();
            return;
        } catch (EncodeException | IOException e) {
            throw new EncodeException(e);
        } finally {
            THREAD_WRITER.set(writer);
        }
    }

    public static Object der(byte[] data) throws ProtoBufException {
        ByteArrayReader reader = new ByteArrayReader(data);
        return AnyCodec.decode(reader);
    }

    public static Object der(byte[] data, int offset, int len) throws ProtoBufException {
        ByteArrayReader reader = new ByteArrayReader(data, offset, len);
        return AnyCodec.decode(reader);
    }

    /**
     * 将protobuf的字节数组，反序列化为对象，如果protobuf字节数组和给定的java类的数据类型不一致
     * 则报错
     * @param <T> 对象类型
     * @param bs 自己数组
     * @param cls 给定的java对象的class
     * @return 返回java对象
     */
    public static <T> T toObject(byte [] bs, Class<T> cls) {
        try {
            return toObject(bs, 0, bs.length, cls);
        } catch (Exception e) {

        }
        return null;
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