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
import io.edap.protobuf.wire.Field.Type;

import java.util.List;

/**
 * Protocol buffer协议的序列化writer接口负责按协议写入数据
 */
public interface ProtoBufWriter {
    BufOut getBufOut();

    void reset();

    int getPos();

    void writeBool(byte[] fieldData, Boolean value);
    void writeBool(byte[] fieldData, boolean value);
    void writePackedBools(byte[] fieldData, List<Boolean> values);

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
    void writePackedFloats(byte[] fieldData, float[] values);
    void writePackedFloats(byte[] fieldData, List<Float> values);
    void writePackedBooleans(byte[] fieldData, boolean[] values);
    void writePackedBooleans(byte[] fieldData, List<Boolean> values);

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
    void writePackedDoubles(byte[] fieldData, double[] values);
    void writePackedDoubles(byte[] fieldData, List<Double> values);

    void writeEnum(byte[] fieldData, Integer value);
    <E extends Enum<E>> void writeListEnum(byte[] fieldData, List<E> vs);
    <E extends ProtoBufEnum> void writeListProtoEnum(byte[] fieldData, List<E> vs);

    void writeString(byte[] fieldData, String value);
    void writeBytes(byte[] fieldData, byte[] value);
    void writeByteArray(byte[] fieldData, byte[] value, int offset, int length);
    void writeByteArray(byte[] value, int offset, int length);

    void writeInt32(int value);
    void writeUInt32(int value);
    void writeFixed32(int value);
    void writeFixed64(long value);
    void writeUInt64(long value);

    <T> void writeMessage(byte[] fieldData, int tag, T msg, ProtoBufEncoder<T> encoder);

    <T> void writeMessages(byte[] fieldData, int tag, T[] msg, ProtoBufEncoder<T> encoder);
    <T> void writeMessages(byte[] fieldData, int tag, List<T> msg, ProtoBufEncoder<T> encoder);

    static int encodeZigZag32(int n) {
        // Note:  the right-shift must be arithmetic
        return (n << 1) ^ (n >> 31);
    }

    static long encodeZigZag64(long n) {
        // Note:  the right-shift must be arithmetic
        return (n << 1) ^ (n >> 63);
    }
}