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

package io.edap.protobuf.reader;

import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoException;

import java.util.ArrayList;
import java.util.List;

import static io.edap.protobuf.ProtoReader.decodeZigZag32;
import static io.edap.protobuf.ProtoReader.decodeZigZag64;
import static io.edap.protobuf.wire.WireFormat.*;

public abstract class AbstractReader implements ProtoBufReader {

    static final ThreadLocal<Integer[]> LOCAL_TMP_INTEGER_ARRAY =
            ThreadLocal.withInitial(() -> new Integer[100]);
    static final ThreadLocal<int[]> LOCAL_TMP_INT_ARRAY =
            ThreadLocal.withInitial(() -> new int[100]);

    static final ThreadLocal<char[]> LOCAL_TMP_CHAR_ARRAY =
            ThreadLocal.withInitial(() -> new char[4096]);

    static final ThreadLocal<Long[]> LOCAL_TMP_LONG_ARRAY =
            ThreadLocal.withInitial(() -> new Long[100]);

    static final ThreadLocal<long[]> LOCAL_TMP_LONG_VALUE_ARRAY =
            ThreadLocal.withInitial(() -> new long[100]);

    @Override
    public boolean readBool() throws ProtoException {
        return readRawVarint64() != 0;
    }

    /**
     * 读取packed编码后的Boolean列表
     * @return 返回boolean的列表
     * @throws ProtoException
     */
    @Override
    public List<Boolean> readPackedBool() throws ProtoException {
        int size = readRawVarint32();
        List<Boolean> list = new ArrayList<>(size);
        for (int i=0;i<size;i++) {
            list.add(readInt32()==1?true:false);
        }
        return list;
    }

    @Override
    public float readFloat() throws ProtoException {
        return Float.intBitsToFloat(readRawLittleEndian32());
    }

    @Override
    public int readInt32() throws ProtoException {
        return readRawVarint32();
    }

    @Override
    public int readUInt32() throws ProtoException {
        return readRawVarint32();
    }

    @Override
    public int readSInt32() throws ProtoException {
        return decodeZigZag32(readRawVarint32());
    }

    @Override
    public int readFixed32() throws ProtoException {
        return readRawLittleEndian32();
    }

    @Override
    public int readSFixed32() throws ProtoException {
        return readRawLittleEndian32();
    }

    @Override
    public List<Float> readPackedFloat() throws ProtoException {
        List<Float> list = new ArrayList<>();
        int size = readRawVarint32();
        for (int i=0;i<size / FIXED_32_SIZE;i++) {
            list.add(readFloat());
        }
        return list;
    }

    @Override
    public Float[] readPackedFloatArray() throws ProtoException {
        int size = readRawVarint32() / FIXED_32_SIZE;
        Float[] array = new Float[size];
        for (int i=0;i<size;i++) {
            array[i] = readFloat();
        }
        return array;
    }

    @Override
    public float[] readPackedFloatArrayValue() throws ProtoException {
        int size = readRawVarint32() / FIXED_32_SIZE;
        float[] array = new float[size];
        for (int i=0;i<size;i++) {
            array[i] = readFloat();
        }
        return array;
    }

    @Override
    public List<Double> readPackedDouble() throws ProtoException {
        int size = readRawVarint32() / FIXED_64_SIZE;
        List<Double> ds = new ArrayList<>(size);
        for (int i=0;i<size;i++) {
            ds.add(readDouble());
        }
        return ds;
    }

    @Override
    public Double[] readPackedDoubleArray() throws ProtoException {
        int size = readRawVarint32() / FIXED_64_SIZE;
        Double[] array = new Double[size];
        for (int i=0;i<size;i++) {
            array[i] = readDouble();
        }
        return array;
    }

    @Override
    public double[] readPackedDoubleArrayValue() throws ProtoException {
        int size = readRawVarint32() / FIXED_64_SIZE;
        double[] array = new double[size];
        for (int i=0;i<size;i++) {
            array[i] = readDouble();
        }
        return array;
    }

    @Override
    public long readInt64() throws ProtoException {
        return readRawVarint64();
    }

    @Override
    public long readUInt64() throws ProtoException {
        return readRawVarint64();
    }

    @Override
    public long readSInt64() throws ProtoException {
        return decodeZigZag64(readRawVarint64());
    }

    @Override
    public long readFixed64() throws ProtoException {
        return readRawLittleEndian64();
    }

    @Override
    public long readSFixed64() throws ProtoException {
        return readRawLittleEndian64();
    }

    @Override
    public double readDouble() throws ProtoException {
        long l = readRawLittleEndian64();
        return Double.longBitsToDouble(l);
    }

    @Override
    public abstract byte[] readBytes() throws ProtoException;

    @Override
    public abstract String readString() throws ProtoException;

    @Override
    public boolean skipField(int tag, int wireType) throws ProtoException {
        wireType = getTagWireType(tag);
        switch (wireType) {
            case 0:  //VARINT
                return skipRawVarint();
            case 1:  //FIXED64
                return skipRawBytes(FIXED_64_SIZE);
            case 2:  //LENGTH_DELIMITED
                int len = readRawVarint32();
                return skipRawBytes(len);
            case 3:  //START_GROUP
                return skipMessage(tag);
            case 5:  //FIXED32
                return skipRawBytes(FIXED_32_SIZE);
            case 6:
                return skipObject();
            default:  //END_GROUP
                return skipString();

        }
    }

    abstract boolean skipRawVarint() throws ProtoException;

    abstract boolean skipString() throws ProtoException;

    abstract boolean skipRawBytes(int len) throws ProtoException;

    abstract boolean skipMessage(int tag) throws ProtoException;

    abstract int readRawVarint32() throws ProtoException;

    abstract long readRawVarint64() throws ProtoException;

    abstract long readRawLittleEndian64() throws ProtoException;

    abstract int readRawLittleEndian32() throws ProtoException;

    abstract boolean skipObject() throws ProtoException;
}
