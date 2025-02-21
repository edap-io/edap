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
import io.edap.io.BufWriter;
import io.edap.protobuf.wire.Field.Type;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Protocol buffer协议的序列化writer接口负责按协议写入数据
 */
public interface ProtoBufWriter extends BufWriter {

    BufOut getBufOut();

    void reset();

    void setPos(int pos);

    void expand(int minLength);

    void writeBool(byte[] fieldData, Boolean value);
    void writeBool(byte[] fieldData, boolean value);
    void writePackedBools(byte[] fieldData, List<Boolean> values);
    void writePackedBools(byte[] fieldData, Iterable<Boolean> values);

    void writeInt32(byte[] fieldData, Integer value);
    void writeInt32(byte[] fieldData, int value);
    void writeUInt32(byte[] fieldData, int value);
    void writeUInt32(byte[] fieldData, Integer value);
    void writeSInt32(byte[] fieldData, Integer value);
    void writeSInt32(byte[] fieldData, int value);


    void writeFixed32(byte[] fieldData, Integer value);
    void writeFixed32(byte[] fieldData, int value);
    void writeSFixed32(byte[] fieldData, Integer value);
    void writeSFixed32(byte[] fieldData, int value);
    void writeFloat(byte[] fieldData, Float value);
    void writeFloat(byte[] fieldData, float value);
    void writePackedInts(byte[] fieldData, int[] values, Type type);
    void writePackedInts(byte[] fieldData, Integer[] values, Type type);
    void writePackedInts(byte[] fieldData, List<Integer> value, Type type);
    void writePackedInts(byte[] fieldData, Iterable<Integer> value, Type type);
    void writePackedFloats(byte[] fieldData, float[] values);
    void writePackedFloats(byte[] fieldData, Float[] values);
    void writePackedFloats(byte[] fieldData, List<Float> values);
    void writePackedFloats(byte[] fieldData, Iterable<Float> values);
    void writePackedBooleans(byte[] fieldData, boolean[] values);
    void writePackedBooleans(byte[] fieldData, Boolean[] values);
    void writePackedBooleans(byte[] fieldData, List<Boolean> values);
    void writePackedBooleans(byte[] fieldData, Iterable<Boolean> values);

    void writeLong(byte[] fieldData, Long value);
    void writeLong(byte[] fieldData, long value);
    void writeInt64(byte[] fieldData, long value);
    void writeUInt64(byte[] fieldData, Long value);
    void writeUInt64(byte[] fieldData, long value);
    void writeSInt64(byte[] fieldData, Long value);
    void writeSInt64(byte[] fieldData, long value);
    void writeFixed64(byte[] fieldData, Long value);
    void writeFixed64(byte[] fieldData, long value);
    void writeSFixed64(byte[] fieldData, Long value);
    void writeSFixed64(byte[] fieldData, long value);
    void writeDouble(byte[] fieldData, Double value);
    void writeDouble(byte[] fieldData, double value);
    void writePackedLongs(byte[] fieldData, Long[] values, Type type);
    void writePackedLongs(byte[] fieldData, long[] values, Type type);
    void writePackedLongs(byte[] fieldData, List<Long> values, Type type);
    void writePackedLongs(byte[] fieldData, Iterable<Long> values, Type type);
    void writePackedDoubles(byte[] fieldData, double[] values);
    void writePackedDoubles(byte[] fieldData, Double[] values);
    void writePackedDoubles(byte[] fieldData, List<Double> values);
    void writePackedDoubles(byte[] fieldData, Iterable<Double> values);

    void writeEnum(byte[] fieldData, Integer value);
    <E extends Enum<E>> void writeArrayEnum(byte[] fieldData, E[] vs);
    <E extends Enum<E>> void writeListEnum(byte[] fieldData, List<E> vs);
    <E extends Enum<E>> void writeListEnum(byte[] fieldData, Iterable<E> vs);
    <E extends ProtoBufEnum> void writeListProtoEnum(byte[] fieldData, List<E> vs);
    <E extends ProtoBufEnum> void writeListProtoEnum(byte[] fieldData, Iterable<E> vs);

    void writeString(String value);
    void writeStringUtf8(String value, int len);
    void writeString(byte[] fieldData, String value);
    void writeBytes(byte[] fieldData, byte[] value);
    void writeBytes(byte[] fieldData, Byte[] value);
    void writeByteArray(byte[] fieldData, byte[] value, int offset, int length);
    void writeByteArray(byte[] value, int offset, int length);
    void writeBytes(byte[] bs);
    void writeByte(byte b);

    void writeInt32(int value);
    void writeInt32(int value, boolean needEncodeZero);
    void writeSInt32(int value, boolean needEncoeZero);
    void writeUInt32(int value);
    void writeFixed32(int value);
    void writeFixed64(long value);
    void writeUInt64(long value);

    void writeObject(byte[] fieldData, Object v) throws EncodeException;
    void writeObject(Object v) throws EncodeException;

    <K,V> void writeMap(byte[] fieldData, Map<K, V> map, MapEntryEncoder<K, V> mapEncoder) throws EncodeException;

    <T> void writeMessage(T msg, ProtoBufEncoder<T> encoder) throws EncodeException;
    <T> void writeMessage(byte[] fieldData, int tag, T msg, ProtoBufEncoder<T> encoder) throws EncodeException;
    <T> void writeMessages(byte[] fieldData, int tag, T[] msg, ProtoBufEncoder<T> encoder) throws EncodeException;
    <T> void writeMessages(byte[] fieldData, int tag, List<T> msg, ProtoBufEncoder<T> encoder) throws EncodeException;
    <T> void writeMessages(byte[] fieldData, int tag, Iterable<T> msg, ProtoBufEncoder<T> encoder) throws EncodeException;

    static int encodeZigZag32(int n) {
        // Note:  the right-shift must be arithmetic
        return (n << 1) ^ (n >> 31);
    }

    static long encodeZigZag64(long n) {
        // Note:  the right-shift must be arithmetic
        return (n << 1) ^ (n >> 63);
    }
}