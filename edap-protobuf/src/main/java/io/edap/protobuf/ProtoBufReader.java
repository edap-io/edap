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

import io.edap.protobuf.wire.Field.Type;

import java.nio.charset.Charset;
import java.util.List;

/**
 * 读取ProtoBuf基本数据的Reader接口
 */
public interface ProtoBufReader {

    public static final Charset CHARSET_UTF8 = Charset.forName("utf-8");

    byte getByte(int pos);

    int getPos();

    byte getByte();

    /**
     * 读取一个boolean的值
     * @return
     * @throws ProtoBufException
     */
    boolean readBool() throws ProtoBufException;
    /**
     * 读取一个float的值
     * @return
     * @throws ProtoBufException
     */
    float readFloat() throws ProtoBufException;
    /**
     * 读取一个Tag的整数
     * @return
     * @throws ProtoBufException
     */
    int readTag() throws ProtoBufException;
    /**
     * 读取一个int32的值
     * @return
     * @throws ProtoBufException
     */
    int readInt32() throws ProtoBufException;
    /**
     * 读取一个uint32的值
     * @return
     * @throws ProtoBufException
     */
    int readUInt32() throws ProtoBufException;
    /**
     * 读取一个sint32的值
     * @return
     * @throws ProtoBufException
     */
    int readSInt32() throws ProtoBufException;
    /**
     * 读取一个fixed32的值
     * @return
     * @throws ProtoBufException
     */
    int readFixed32() throws ProtoBufException;
    /**
     * 读取一个sfixed32的值
     * @return
     * @throws ProtoBufException
     */
    int readSFixed32() throws ProtoBufException;
    /**
     * 读取packed编码后的Boolean列表
     * @return 返回boolean的列表
     * @throws ProtoBufException
     */
    List<Boolean> readPackedBool() throws ProtoBufException;
    /**
     * 读取packed编码后的Boolean列表
     * @return 返回boolean的列表
     * @throws ProtoBufException
     */
    boolean[] readPackedBoolValues() throws ProtoBufException;
    Boolean[] readPackedBools() throws ProtoBufException;
    /**
     * 读取packed编码后的Integer到List中
     * @param type 属性的protobuf的数据类型
     * @throws ProtoBufException
     */
    List<Integer> readPackedInt32(Type type) throws ProtoBufException;
    /**
     * 读取packed编码的int数组
     * @param type 属性的protobuf的数据类型
     * @return
     * @throws ProtoBufException
     */
    Integer[] readPackedInt32Array(Type type) throws ProtoBufException;

    /**
     * 读取packed编码的int数组
     * @param type 属性的protobuf的数据类型
     * @return
     * @throws ProtoBufException
     */
    int[] readPackedInt32ArrayValue(Type type) throws ProtoBufException;
    /**
     * 读取packed编码后的Float到List中
     * @throws ProtoBufException
     */
    List<Float> readPackedFloat() throws ProtoBufException;
    /**
     * 读取packed编码的float数组
     * @return
     * @throws ProtoBufException
     */
    Float[] readPackedFloatArray() throws ProtoBufException;
    /**
     * 读取packed编码的float数组
     * @return
     * @throws ProtoBufException
     */
    float[] readPackedFloatArrayValue() throws ProtoBufException;
    /**
     * 读取packed编码后的Long到给定的List中
     * @param type 属性的protobuf的数据类型
     * @throws ProtoBufException
     */
    List<Long> readPackedInt64(Type type) throws ProtoBufException;
    /**
     * 读取packed编码的long数组
     * @param type 属性的protobuf的数据类型
     * @return
     * @throws ProtoBufException
     */
    Long[] readPackedInt64Array(Type type) throws ProtoBufException;
    /**
     * 读取packed编码的long数组
     * @param type 属性的protobuf的数据类型
     * @return
     * @throws ProtoBufException
     */
    long[] readPackedInt64ArrayValue(Type type) throws ProtoBufException;
    /**
     * 读取packed编码的Double到给定的List容器中
     * @return 解码的double列表
     * @throws ProtoBufException
     */
    List<Double> readPackedDouble() throws ProtoBufException;
    /**
     * 读取packed编码的Double到给定的List中
     * @return
     * @throws ProtoBufException
     */
    Double[] readPackedDoubleArray() throws ProtoBufException;
    /**
     * 读取packed编码的Double到给定的List中
     * @return
     * @throws ProtoBufException
     */
    double[] readPackedDoubleArrayValue() throws ProtoBufException;

    /**
     * 读取一个int64的值
     * @return
     * @throws ProtoBufException
     */
    long readInt64() throws ProtoBufException;
    /**
     * 读取一个uint64的值
     * @return
     * @throws ProtoBufException
     */
    long readUInt64() throws ProtoBufException;
    /**
     * 读取一个sint64的值
     * @return
     * @throws ProtoBufException
     */
    long readSInt64() throws ProtoBufException;
    /**
     * 读取一个fixed64的值
     * @return
     * @throws ProtoBufException
     */
    long readFixed64() throws ProtoBufException;
    /**
     * 读取一个sfixed64的值
     * @return
     * @throws ProtoBufException
     */
    long readSFixed64() throws ProtoBufException;
    /**
     * 读取一个double的值
     * @return
     * @throws ProtoBufException
     */
    double readDouble() throws ProtoBufException;
    /**
     * 读取一个byte[]
     * @return
     * @throws ProtoBufException
     */
    byte[] readBytes() throws ProtoBufException;
    /**
     * 读取String的值
     * @return
     * @throws ProtoBufException
     */
    String readString() throws ProtoBufException;

    /**
     * 读取指定长度的字符串
     * @param len
     * @return
     * @throws ProtoBufException
     */
    String readString(int len) throws ProtoBufException;

    /**
     * 读取Any的值
     * @return
     * @throws ProtoBufException
     */
    Object readObject() throws ProtoBufException;

    /**
     * 读取一个Message的对象
     * @param <T>
     * @param decoder
     * @return
     * @throws ProtoBufException
     */
    <T extends Object> T readMessage(ProtoBufDecoder<T> decoder) throws ProtoBufException;
    /**
     * 跳过一个Tag
     * @param tag tag的序号
     * @param wireType tag的类型
     * @return
     * @throws ProtoBufException
     */
    boolean skipField(int tag, int wireType) throws ProtoBufException;

    void reset();

    /**
     * 解码ZigZag编码的int32的值
     * @param n 解码前int值
     * @return
     */
    static int decodeZigZag32(final int n) {
        return (n >>> 1) ^ -(n & 1);
    }
    /**
     * 解码ZigZag编码的int64的值
     * @param n 解码前的long值
     * @return
     */
    static long decodeZigZag64(final long n) {
        return (n >>> 1) ^ -(n & 1);
    }
}