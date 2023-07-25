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

import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.ProtoBufReader;

import java.util.ArrayList;
import java.util.List;

import static io.edap.protobuf.ProtoBufReader.decodeZigZag32;
import static io.edap.protobuf.ProtoBufReader.decodeZigZag64;
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
    public boolean readBool() throws ProtoBufException {
        return readRawVarint64() != 0;
    }

    /**
     * 读取packed编码后的Boolean列表
     * @return 返回boolean的列表
     * @throws ProtoBufException
     */
    @Override
    public List<Boolean> readPackedBool() throws ProtoBufException {
        int size = readRawVarint32();
        List<Boolean> list = new ArrayList<>(size);
        for (int i=0;i<size;i++) {
            list.add(readInt32()==1?true:false);
        }
        return list;
    }

    @Override
    public float readFloat() throws ProtoBufException {
        return Float.intBitsToFloat(readRawLittleEndian32());
    }

    @Override
    public int readInt32() throws ProtoBufException {
        return readRawVarint32();
    }

    @Override
    public int readUInt32() throws ProtoBufException {
        return readRawVarint32();
    }

    @Override
    public int readSInt32() throws ProtoBufException {
        return decodeZigZag32(readRawVarint32());
    }

    @Override
    public int readFixed32() throws ProtoBufException {
        return readRawLittleEndian32();
    }

    @Override
    public int readSFixed32() throws ProtoBufException {
        return readRawLittleEndian32();
    }

    @Override
    public List<Float> readPackedFloat() throws ProtoBufException {
        List<Float> list = new ArrayList<>();
        int size = readRawVarint32();
        for (int i=0;i<size / FIXED_32_SIZE;i++) {
            list.add(readFloat());
        }
        return list;
    }

    @Override
    public Float[] readPackedFloatArray() throws ProtoBufException {
        int size = readRawVarint32() / FIXED_32_SIZE;
        Float[] array = new Float[size];
        for (int i=0;i<size;i++) {
            array[i] = readFloat();
        }
        return array;
    }

    @Override
    public float[] readPackedFloatArrayValue() throws ProtoBufException {
        int size = readRawVarint32() / FIXED_32_SIZE;
        float[] array = new float[size];
        for (int i=0;i<size;i++) {
            array[i] = readFloat();
        }
        return array;
    }

    @Override
    public List<Double> readPackedDouble() throws ProtoBufException {
        int size = readRawVarint32() / FIXED_64_SIZE;
        List<Double> ds = new ArrayList<>(size);
        for (int i=0;i<size;i++) {
            ds.add(readDouble());
        }
        return ds;
    }

    @Override
    public Double[] readPackedDoubleArray() throws ProtoBufException {
        int size = readRawVarint32() / FIXED_64_SIZE;
        Double[] array = new Double[size];
        for (int i=0;i<size;i++) {
            array[i] = readDouble();
        }
        return array;
    }

    @Override
    public double[] readPackedDoubleArrayValue() throws ProtoBufException {
        int size = readRawVarint32() / FIXED_64_SIZE;
        double[] array = new double[size];
        for (int i=0;i<size;i++) {
            array[i] = readDouble();
        }
        return array;
    }

    @Override
    public long readInt64() throws ProtoBufException {
        return readRawVarint64();
    }

    @Override
    public long readUInt64() throws ProtoBufException {
        return readRawVarint64();
    }

    @Override
    public long readSInt64() throws ProtoBufException {
        return decodeZigZag64(readRawVarint64());
    }

    @Override
    public long readFixed64() throws ProtoBufException {
        return readRawLittleEndian64();
    }

    @Override
    public long readSFixed64() throws ProtoBufException {
        return readRawLittleEndian64();
    }

    @Override
    public double readDouble() throws ProtoBufException {
        long l = readRawLittleEndian64();
        return Double.longBitsToDouble(l);
    }

    @Override
    public abstract byte[] readBytes() throws ProtoBufException;

    @Override
    public abstract String readString() throws ProtoBufException;

    @Override
    public boolean skipField(int tag, int wireType) throws ProtoBufException {
        wireType = getTagWireType(tag);
        switch (wireType) {
            case 0:  //VARINT
                return skipRawVarint();
            case 5:  //FIXED32
                return skipRawBytes(FIXED_32_SIZE);
            case 1:  //FIXED64
                return skipRawBytes(FIXED_64_SIZE);
            case 2:  //LENGTH_DELIMITED
                int len = readRawVarint32();
                if (len >= 0) {
                    return skipRawBytes(len);
                } else {
                    throw ProtoBufException.malformedVarint();
                }
            case 3:  //START_GROUP
                return skipMessage(tag);
            case 7:
                return skipString();
            case 4:  //END_GROUP
                return true;

        }
        return false;
    }

    abstract boolean skipRawVarint() throws ProtoBufException;

    abstract boolean skipString() throws ProtoBufException;

    abstract boolean skipRawBytes(int len) throws ProtoBufException;

    abstract boolean skipMessage(int tag) throws ProtoBufException;

    abstract int readRawVarint32() throws ProtoBufException;

    abstract long readRawVarint64() throws ProtoBufException;

    abstract long readRawLittleEndian64() throws ProtoBufException;

    abstract int readRawLittleEndian32() throws ProtoBufException;
}
