package io.edap.eproto;

import io.edap.io.BufOut;
import io.edap.io.BufWriter;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufEnum;
import io.edap.protobuf.wire.Field.Type;

import java.util.List;

public interface EprotoWriter extends BufWriter {

    BufOut getBufOut();

    void reset();

    int size();

    /**
     * 编码字符串，编码字符串的编码方法为如果为null则使用'\0'表示，如果字符串长度为0则使用'0',
     * 如果字符串长度不为0，则先使用varint的方式编码字符串长度，然后使用utf8编码写入utf8编码的
     * 字节数组
     * @param s 需要编码的字符串
     */
    void writeString(String s);

    /**
     * 编码byte数组的数据，先编码长度，然后写入对应长的byte数组
     * @param bs 需要编码的byte数组
     */
    void writeBytes(byte[] bs);

    void writeBool(Boolean value);
    void writeBool(boolean value);
    void writePackedBools(List<Boolean> values);
    void writePackedBools(Iterable<Boolean> values);

    void writeInt32(Integer value);
    void writeInt32(int value);
    void writeUInt32(int value);
    void writeUInt32(Integer value);
    void writeSInt32(Integer value);
    void writeSInt32(int value);

    void writeFixed32(Integer value);
    void writeFixed32(int value);
    void writeSFixed32(Integer value);
    void writeSFixed32(int value);
    void writeFloat(Float value);
    void writeFloat(float value);

    void writePackedInts(int[] values, Type type);
    void writePackedInts(Integer[] values, Type type);
    void writePackedInts(List<Integer> value, Type type);
    void writePackedInts(Iterable<Integer> value, Type type);
    void writePackedFloats(float[] values);
    void writePackedFloats(Float[] values);
    void writePackedFloats(List<Float> values);
    void writePackedFloats(Iterable<Float> values);
    void writePackedBooleans(boolean[] values);
    void writePackedBooleans(Boolean[] values);

    void writeLong(Long value);
    void writeLong(long value);
    void writeInt64(long value);
    void writeUInt64(Long value);
    void writeUInt64(long value);
    void writeSInt64(Long value);
    void writeSInt64(long value);
    void writeFixed64(Long value);
    void writeFixed64(long value);
    void writeSFixed64(Long value);
    void writeSFixed64(long value);
    void writeDouble(Double value);
    void writeDouble(double value);
    void writePackedLongs(Long[] values, Type type);
    void writePackedLongs(long[] values, Type type);
    void writePackedLongs(List<Long> values, Type type);
    void writePackedLongs(Iterable<Long> values, Type type);
    void writePackedDoubles(double[] values);
    void writePackedDoubles(Double[] values);
    void writePackedDoubles(List<Double> values);
    void writePackedDoubles(Iterable<Double> values);

    <E extends Enum<E>> void writeArrayEnum(E[] vs);
    <E extends Enum<E>> void writeListEnum(List<E> vs);
    <E extends Enum<E>> void writeListEnum(Iterable<E> vs);
    <E extends ProtoBufEnum> void writeListProtoEnum(List<E> vs);
    <E extends ProtoBufEnum> void writeListProtoEnum(Iterable<E> vs);

    void writeBytes(Byte[] value);
    void writeByteArray(byte[] value, int offset, int length);
    void writeByte(byte b);

    void writeObject(Object v) throws EncodeException;

    <T> void writeMessage(T msg, EprotoEncoder<T> encoder) throws EncodeException;
    <T> void writeMessages(T[] msg, EprotoEncoder<T> encoder) throws EncodeException;
    <T> void writeMessages(List<T> msg, EprotoEncoder<T> encoder) throws EncodeException;
    <T> void writeMessages(Iterable<T> msg, EprotoEncoder<T> encoder) throws EncodeException;

    static int encodeZigZag32(int n) {
        // Note:  the right-shift must be arithmetic
        return (n << 1) ^ (n >> 31);
    }

    static long encodeZigZag64(long n) {
        // Note:  the right-shift must be arithmetic
        return (n << 1) ^ (n >> 63);
    }
}
