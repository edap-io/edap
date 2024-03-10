/*
 * Copyright 2024 The edap Project
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

import java.util.List;

import io.edap.protobuf.wire.Field.Type;

public interface ProtoReader {
    void skip(int len);

    boolean isFastCodec();

    byte getByte(int pos);

    int getPos();

    byte getByte();

    /**
     * 读取一个boolean的值
     * @return
     * @throws ProtoException
     */
    boolean readBool() throws ProtoException;
    /**
     * 读取一个float的值
     * @return
     * @throws ProtoException
     */
    float readFloat() throws ProtoException;
    /**
     * 读取一个Tag的整数
     * @return
     * @throws ProtoException
     */
    int readTag() throws ProtoException;
    /**
     * 读取一个int32的值
     * @return
     * @throws ProtoException
     */
    int readInt32() throws ProtoException;
    /**
     * 读取一个uint32的值
     * @return
     * @throws ProtoException
     */
    int readUInt32() throws ProtoException;
    /**
     * 读取一个sint32的值
     * @return
     * @throws ProtoException
     */
    int readSInt32() throws ProtoException;
    /**
     * 读取一个fixed32的值
     * @return
     * @throws ProtoException
     */
    int readFixed32() throws ProtoException;
    /**
     * 读取一个sfixed32的值
     * @return
     * @throws ProtoException
     */
    int readSFixed32() throws ProtoException;
    /**
     * 读取packed编码后的Boolean列表
     * @return 返回boolean的列表
     * @throws ProtoException
     */
    List<Boolean> readPackedBool() throws ProtoException;
    /**
     * 读取packed编码后的Boolean列表
     * @return 返回boolean的列表
     * @throws ProtoException
     */
    boolean[] readPackedBoolValues() throws ProtoException;
    Boolean[] readPackedBools() throws ProtoException;
    /**
     * 读取packed编码后的Integer到List中
     * @param type 属性的protobuf的数据类型
     * @throws ProtoException
     */
    List<Integer> readPackedInt32(Type type) throws ProtoException;
    /**
     * 读取packed编码的int数组
     * @param type 属性的protobuf的数据类型
     * @return
     * @throws ProtoException
     */
    Integer[] readPackedInt32Array(Type type) throws ProtoException;

    /**
     * 读取packed编码的int数组
     * @param type 属性的protobuf的数据类型
     * @return
     * @throws ProtoException
     */
    int[] readPackedInt32ArrayValue(Type type) throws ProtoException;
    /**
     * 读取packed编码后的Float到List中
     * @throws ProtoException
     */
    List<Float> readPackedFloat() throws ProtoException;
    /**
     * 读取packed编码的float数组
     * @return
     * @throws ProtoException
     */
    Float[] readPackedFloatArray() throws ProtoException;
    /**
     * 读取packed编码的float数组
     * @return
     * @throws ProtoException
     */
    float[] readPackedFloatArrayValue() throws ProtoException;
    /**
     * 读取packed编码后的Long到给定的List中
     * @param type 属性的protobuf的数据类型
     * @throws ProtoException
     */
    List<Long> readPackedInt64(Type type) throws ProtoException;
    /**
     * 读取packed编码的long数组
     * @param type 属性的protobuf的数据类型
     * @return
     * @throws ProtoException
     */
    Long[] readPackedInt64Array(Type type) throws ProtoException;
    /**
     * 读取packed编码的long数组
     * @param type 属性的protobuf的数据类型
     * @return
     * @throws ProtoException
     */
    long[] readPackedInt64ArrayValue(Type type) throws ProtoException;
    /**
     * 读取packed编码的Double到给定的List容器中
     * @return 解码的double列表
     * @throws ProtoException
     */
    List<Double> readPackedDouble() throws ProtoException;
    /**
     * 读取packed编码的Double到给定的List中
     * @return
     * @throws ProtoException
     */
    Double[] readPackedDoubleArray() throws ProtoException;
    /**
     * 读取packed编码的Double到给定的List中
     * @return
     * @throws ProtoException
     */
    double[] readPackedDoubleArrayValue() throws ProtoException;

    /**
     * 读取一个int64的值
     * @return
     * @throws ProtoException
     */
    long readInt64() throws ProtoException;
    /**
     * 读取一个uint64的值
     * @return
     * @throws ProtoException
     */
    long readUInt64() throws ProtoException;
    /**
     * 读取一个sint64的值
     * @return
     * @throws ProtoException
     */
    long readSInt64() throws ProtoException;
    /**
     * 读取一个fixed64的值
     * @return
     * @throws ProtoException
     */
    long readFixed64() throws ProtoException;
    /**
     * 读取一个sfixed64的值
     * @return
     * @throws ProtoException
     */
    long readSFixed64() throws ProtoException;
    /**
     * 读取一个double的值
     * @return
     * @throws ProtoException
     */
    double readDouble() throws ProtoException;
    /**
     * 读取一个byte[]
     * @return
     * @throws ProtoException
     */
    byte[] readBytes() throws ProtoException;
    /**
     * 读取String的值
     * @return
     * @throws ProtoException
     */
    String readString() throws ProtoException;

    /**
     * 读取指定长度的字符串
     * @param len
     * @return
     * @throws ProtoException
     */
    String readString(int len) throws ProtoException;

    /**
     * 读取Any的值
     * @return
     * @throws ProtoException
     */
    Object readObject() throws ProtoException;

    /**
     * 读取一个Message的对象
     * @param <T>
     * @param decoder
     * @return
     * @throws ProtoException
     */
    <T extends Object> T readMessage(ProtoDecoder<T> decoder) throws ProtoException;

    /**
     * 使用GROUP包含时，解码Message
     * @param decoder 解码器
     * @param endTag 结束的tag值，该值为tag和END_GROUP计算后的值
     * @return
     * @param <T>
     * @throws ProtoException
     */
    default <T extends Object> T readMessage(ProtoDecoder<T> decoder, int endTag) throws ProtoException {
        throw new RuntimeException("Not support method readMessage(ProtoBufDecoder<T> decoder, int endTag)");
    }
    /**
     * 跳过一个Tag
     * @param tag tag的序号
     * @param wireType tag的类型
     * @return
     * @throws ProtoException
     */
    boolean skipField(int tag, int wireType) throws ProtoException;

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
