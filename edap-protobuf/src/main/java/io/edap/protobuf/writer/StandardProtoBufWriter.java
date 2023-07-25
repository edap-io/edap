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

package io.edap.protobuf.writer;

import io.edap.io.BufOut;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ext.AnyCodec;
import io.edap.protobuf.internal.ProtoBufOut;
import io.edap.protobuf.wire.Field;
import io.edap.util.CollectionUtils;

import java.util.*;

import static io.edap.protobuf.ProtoBufWriter.encodeZigZag32;
import static io.edap.protobuf.ProtoBufWriter.encodeZigZag64;
import static io.edap.protobuf.util.ProtoUtil.*;
import static io.edap.protobuf.wire.WireFormat.MAX_VARINT_SIZE;
import static io.edap.protobuf.wire.WireFormat.MAX_VARLONG_SIZE;
import static io.edap.util.CollectionUtils.isEmpty;

public class StandardProtoBufWriter extends AbstractWriter {

    public StandardProtoBufWriter(BufOut out) {
        super(out);
    }

    @Override
    public void writePackedInts(byte[] fieldData, int[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int size = values.length;
        int len;
        switch (type) {
            case INT32:
            case UINT32:
                size = values.length;
                len = size * MAX_VARINT_SIZE;
                expand((MAX_VARLONG_SIZE << 1) + len);
                writeFieldData(fieldData);
                int oldPos = pos;

                pos += 1;
                int i = 0;
                writeInt32_0(values[i++]);
                if (size > 1) {
                    writeInt32_0(values[i++]);
                }
                if (size > 2) {
                    writeInt32_0(values[i++]);
                }
                if (size > 3) {
                    writeInt32_0(values[i++]);
                }
                if (size > 4) {
                    writeInt32_0(values[i++]);
                }
                if (size > 5) {
                    writeInt32_0(values[i++]);
                }
                if (size > 6) {
                    writeInt32_0(values[i++]);
                }
                if (size > 7) {
                    writeInt32_0(values[i++]);
                }
                if (size > 8) {
                    writeInt32_0(values[i++]);
                }
                if (size > 9) {
                    writeInt32_0(values[i++]);
                }
                if (size > 10) {
                    for (i=10;i<size;i++) {
                        writeInt32_0(values[i]);
                    }
                }
                len = pos - oldPos - 1;
                pos += writeLenMoveBytes(bs, oldPos, len);
                return;
            case SINT32:
                expand(MAX_VARINT_SIZE << 1 + size*MAX_VARINT_SIZE);
                writeFieldData(fieldData);
                oldPos = pos;
                pos++;
                //writeUInt32_0(len);
                for (i=0;i<size;i++) {
                    writeUInt32_0(encodeZigZag32(values[i]));
                }
                len = pos - oldPos - 1;
                pos += writeLenMoveBytes(bs, oldPos, len);
                return;
            case FIXED32:
            case SFIXED32:
                expand(MAX_VARINT_SIZE << 1 + size << 2);
                writeFieldData(fieldData);
                writeUInt32_0(size << 2);
                for (i=0;i<size;i++) {
                    writeFixed32_0(values[i]);
                }
            default:
                break;
        }
    }

    @Override
    public void writePackedInts(byte[] fieldData, Integer[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int size = values.length;
        int len;
        switch (type) {
            case INT32:
            case UINT32:
                size = values.length;
                len = size * MAX_VARINT_SIZE;
                expand((MAX_VARINT_SIZE << 1) + len);
                writeFieldData(fieldData);
                int oldPos = pos;

                pos += 1;
                int i = 0;
                writeInt32_0(values[i++]);
                if (size > 1) {
                    writeInt32_0(values[i++]);
                }
                if (size > 2) {
                    writeInt32_0(values[i++]);
                }
                if (size > 3) {
                    writeInt32_0(values[i++]);
                }
                if (size > 4) {
                    writeInt32_0(values[i++]);
                }
                if (size > 5) {
                    writeInt32_0(values[i++]);
                }
                if (size > 6) {
                    writeInt32_0(values[i++]);
                }
                if (size > 7) {
                    writeInt32_0(values[i++]);
                }
                if (size > 8) {
                    writeInt32_0(values[i++]);
                }
                if (size > 9) {
                    writeInt32_0(values[i++]);
                }
                if (size > 10) {
                    for (i=10;i<size;i++) {
                        writeInt32_0(values[i]);
                    }
                }

                len = pos - oldPos - 1;
                pos += writeLenMoveBytes(bs, oldPos, len);
                return;
            case SINT32:
                len = size * MAX_VARINT_SIZE;
                expand(MAX_VARINT_SIZE << 1 + len);
                writeFieldData(fieldData);
                oldPos = pos;
                pos++;
                for (i=0;i<size;i++) {
                    writeUInt32_0(encodeZigZag32(values[i]));
                }
                len = pos - oldPos - 1;
                pos += writeLenMoveBytes(bs, oldPos, len);
                return;
            case FIXED32:
            case SFIXED32:
                expand(MAX_VARINT_SIZE << 1 + size << 2);
                writeFieldData(fieldData);
                writeUInt32_0(size << 2);
                for (i=0;i<size;i++) {
                    writeFixed32_0(values[i]);
                }
            default:
                break;
        }
    }

    @Override
    public void writePackedInts(byte[] fieldData, List<Integer> values, Field.Type type) {
        if (isEmpty(values)) {
            return;
        }
        int len;
        int size;
        switch (type) {
            case INT32:
            case UINT32:

//                len = 0;
//                for (Integer i : values) {
//                    len += computeRawVarint32Size(i);
//                }
//                expand(wbuf, MAX_VARINT_SIZE << 1 + len);
//                writeFieldData(fieldData);
//                writeUInt32_0(values.size());
//                for (Integer i : values) {
//                    writeUInt32_0(i);
//                }
//
                size = values.size();
                len = size * 5;
                expand((MAX_VARLONG_SIZE << 1) + len);
                writeFieldData(fieldData);
                int oldPos = pos;

                pos += 1;
                int i = 0;
                writeInt32_0(values.get(i++));
                if (size > 1) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 2) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 3) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 4) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 5) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 6) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 7) {
                    writeUInt32_0(values.get(i++));
                }
                if (size > 8) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 9) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 10) {
                    for (i=10;i<size;i++) {
                        writeInt32_0(values.get(i));
                    }
                }

                len = pos - oldPos - 1;
                pos += writeLenMoveBytes(bs, oldPos, len);

//                ProtoBufWriter twriter = getLocalWriter();
//                try {
//                    int size = values.size();
//                    for (int i = 0; i < size; i++) {
//                        twriter.writeInt32(values.get(i));
//                    }
//                    len = twriter.getBufOut().getWriteBuf().start;
//                    writeByteArray(fieldData, 0, fieldData.length);
//                    writeUInt32(len);
//                    writeByteArray(twriter.getBufOut().getWriteBuf().bs, 0, len);
//                } finally {
//                    releaseLocalWriter(twriter);
//                }
                return;
            case SINT32:
                len = values.size() * MAX_VARINT_SIZE;
                expand(MAX_VARINT_SIZE << 1 + len);
                writeFieldData(fieldData);
                oldPos = pos;
                pos++;
                for (Integer v : values) {
                    writeUInt32_0(encodeZigZag32(v));
                }
                len = pos - oldPos - 1;
                pos += writeLenMoveBytes(bs, oldPos, len);
                return;
            case FIXED32:
            case SFIXED32:
                size = values.size();
                expand(MAX_VARINT_SIZE << 1 + size << 2);
                writeFieldData(fieldData);
                writeUInt32_0(size << 2);
                for (Integer v : values) {
                    writeFixed32_0(v);
                }
            default:
                break;
        }
    }

    @Override
    public void writePackedInts(byte[] fieldData, Iterable<Integer> values, Field.Type type) {
        if (isEmpty(values)) {
            return;
        }
        int len;
        int size;
        size = 0;
        Iterator<Integer> itr = values.iterator();
        while (itr.hasNext()) {
            itr.next();
            size++;
        }
        switch (type) {
            case INT32:
            case UINT32:
                len = size * 5;
                expand((MAX_VARLONG_SIZE << 1) + len);
                writeFieldData(fieldData);
                int oldPos = pos;

                pos += 1;
                int i = 0;
                Iterator<Integer> iterator = values.iterator();
                writeInt32_0(iterator.next());
                if (size > 1) {
                    writeInt32_0(iterator.next());
                }
                if (size > 2) {
                    writeInt32_0(iterator.next());
                }
                if (size > 3) {
                    writeInt32_0(iterator.next());
                }
                if (size > 4) {
                    writeInt32_0(iterator.next());
                }
                if (size > 5) {
                    writeInt32_0(iterator.next());
                }
                if (size > 6) {
                    writeInt32_0(iterator.next());
                }
                if (size > 7) {
                    writeUInt32_0(iterator.next());
                }
                if (size > 8) {
                    writeInt32_0(iterator.next());
                }
                if (size > 9) {
                    writeInt32_0(iterator.next());
                }
                if (size > 10) {
                    while (iterator.hasNext()) {
                        writeInt32_0(iterator.next());
                    }
                }

                len = pos - oldPos - 1;
                pos += writeLenMoveBytes(bs, oldPos, len);

//                ProtoBufWriter twriter = getLocalWriter();
//                try {
//                    int size = values.size();
//                    for (int i = 0; i < size; i++) {
//                        twriter.writeInt32(values.get(i));
//                    }
//                    len = twriter.getBufOut().getWriteBuf().start;
//                    writeByteArray(fieldData, 0, fieldData.length);
//                    writeUInt32(len);
//                    writeByteArray(twriter.getBufOut().getWriteBuf().bs, 0, len);
//                } finally {
//                    releaseLocalWriter(twriter);
//                }
                return;
            case SINT32:
                len = size * MAX_VARINT_SIZE;
                expand(MAX_VARINT_SIZE << 1 + len);
                writeFieldData(fieldData);
                oldPos = pos;
                pos++;
                for (Integer v : values) {
                    writeUInt32_0(encodeZigZag32(v));
                }
                len = pos - oldPos - 1;
                pos += writeLenMoveBytes(bs, oldPos, len);
                return;
            case FIXED32:
            case SFIXED32:
                expand(MAX_VARINT_SIZE << 1 + size << 2);
                writeFieldData(fieldData);
                writeUInt32_0(size << 2);
                for (Integer v : values) {
                    writeFixed32_0(v);
                }
            default:
                break;
        }
    }

    @Override
    public void writePackedFloats(byte[] fieldData, float[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length << 2;
        expand(MAX_VARINT_SIZE << 1 + len);
        writeFieldData(fieldData);
        writeUInt32_0(len);
        for (float value : values) {
            writeFixed32_0(Float.floatToRawIntBits(value));
        }
    }

    @Override
    public void writePackedFloats(byte[] fieldData, Float[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length << 2;
        expand(MAX_VARINT_SIZE << 1 + len);
        writeFieldData(fieldData);
        writeUInt32_0(len);
        for (float value : values) {
            writeFixed32_0(Float.floatToRawIntBits(value));
        }
    }

    @Override
    public void writePackedFloats(byte[] fieldData, List<Float> values) {
        if (isEmpty(values)) {
            return;
        }
        int len = values.size() << 2;
        expand( MAX_VARINT_SIZE << 1 + len);
        writeFieldData(fieldData);
        writeUInt32(len);
        for (Float value : values) {
            writeFixed32_0(Float.floatToRawIntBits(value));
        }
    }

    @Override
    public void writePackedFloats(byte[] fieldData, Iterable<Float> values) {
        if (isEmpty(values)) {
            return;
        }
        int size = 0;
        for (Float f : values) {
            size++;
        }
        int len = size << 2;
        expand( MAX_VARINT_SIZE << 1 + len);
        writeFieldData(fieldData);
        writeUInt32(len);
        for (Float value : values) {
            writeFixed32_0(Float.floatToRawIntBits(value));
        }
    }

    @Override
    public void writePackedBooleans(byte[] fieldData, boolean[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        expand(MAX_VARINT_SIZE + values.length);
        writeFieldData(fieldData);
        writeUInt32(values.length);
        for (boolean value : values) {
            writeUInt32_0(value?1:0);
        }
    }

    @Override
    public void writePackedBooleans(byte[] fieldData, Boolean[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        expand(MAX_VARINT_SIZE + values.length);
        writeFieldData(fieldData);
        writeUInt32(values.length);
        for (boolean value : values) {
            writeUInt32_0(value?1:0);
        }
    }

    @Override
    public void writePackedBooleans(byte[] fieldData, List<Boolean> values) {
        if (isEmpty(values)) {
            return;
        }
        expand(MAX_VARINT_SIZE + values.size());
        writeFieldData(fieldData);
        writeUInt32(values.size());
        for (boolean value : values) {
            writeUInt32_0(value?1:0);
        }
    }

    @Override
    public void writePackedBooleans(byte[] fieldData, Iterable<Boolean> values) {
        if (values==null || !values.iterator().hasNext()) {
            return;
        }
        int size = 0;
        for (Boolean b : values) {
            size++;
        }
        expand(MAX_VARINT_SIZE + size);
        writeFieldData(fieldData);
        writeUInt32(size);
        for (boolean value : values) {
            writeUInt32_0(value?1:0);
        }
    }

    @Override
    public void writePackedLongs(byte[] fieldData, Long[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int len;
        int size = values.length;
        switch (type) {
            case INT64:
            case UINT64:
                expand(MAX_VARINT_SIZE);
                writeFieldData(fieldData);
                int oldPos = pos;
                pos++;
                expand(MAX_VARLONG_SIZE * values.length);
                for (int i=0;i<size;i++) {
                    writeUInt64_0(values[i]);
                }
                len = pos - oldPos - 1;
                pos += writeLenMoveBytes(bs, oldPos, len);
                return;
            case SINT64:
                expand(MAX_VARINT_SIZE);
                writeFieldData(fieldData);
                oldPos = pos;
                pos++;
                expand(MAX_VARLONG_SIZE * values.length);
                for (int i=0;i<size;i++) {
                    writeUInt64_0(encodeZigZag64(values[i]));
                }
                len = pos - oldPos - 1;
                pos += writeLenMoveBytes(bs, oldPos, len);
                return;
            case FIXED64:
            case SFIXED64:
                expand(MAX_VARINT_SIZE << 1 + size << 3);
                writeFieldData(fieldData);
                writeUInt32_0(size << 3);
                for (int i=0;i<size;i++) {
                    writeFixed64_0(values[i]);
                }
            default:

        }
    }

    @Override
    public void writePackedLongs(byte[] fieldData, long[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int len;
        int size = values.length;
        switch (type) {
            case INT64:
            case UINT64:
                expand(MAX_VARINT_SIZE << 1 + MAX_VARLONG_SIZE * values.length);
                writeFieldData(fieldData);
                int oldPos = pos;
                pos++;
                for (int i=0;i<size;i++) {
                    writeUInt64_0(values[i]);
                }
                len = pos - oldPos - 1;
                pos += writeLenMoveBytes(bs, oldPos, len);
                return;
            case SINT64:
                expand(MAX_VARINT_SIZE << 1 + MAX_VARLONG_SIZE * values.length);
                writeFieldData(fieldData);
                oldPos = pos;
                pos++;
                for (int i=0;i<size;i++) {
                    writeUInt64_0(encodeZigZag64(values[i]));
                }
                len = pos - oldPos - 1;
                pos += writeLenMoveBytes(bs, oldPos, len);
                return;
            case FIXED64:
            case SFIXED64:
                expand(MAX_VARINT_SIZE << 1 + size << 3);
                writeFieldData(fieldData);
                writeUInt32_0(size << 3);
                for (int i=0;i<size;i++) {
                    writeFixed64_0(values[i]);
                }
            default:

        }
    }

    @Override
    public void writePackedLongs(byte[] fieldData, List<Long> values, Field.Type type) {
        if (isEmpty(values)) {
            return;
        }
        int len;
        switch (type) {
            case INT64:
            case UINT64:
                expand(MAX_VARINT_SIZE << 1 + MAX_VARLONG_SIZE * values.size());
                writeFieldData(fieldData);
                int oldPos = pos;
                pos++;
                for (long l : values) {
                    writeUInt64_0(l);
                }
                len = pos - oldPos - 1;
                pos += writeLenMoveBytes(bs, oldPos, len);
                return;
            case SINT64:
                expand(MAX_VARINT_SIZE << 1 + MAX_VARLONG_SIZE * values.size());
                writeFieldData(fieldData);
                oldPos = pos;
                pos++;
                for (long l : values) {
                    writeUInt64_0(encodeZigZag64(l));
                }
                len = pos - oldPos - 1;
                pos += writeLenMoveBytes(bs, oldPos, len);
                return;
            case FIXED64:
            case SFIXED64:
                int size = values.size();
                expand(MAX_VARINT_SIZE << 1 + size << 3);
                writeFieldData(fieldData);
                writeUInt32_0(size << 3);
                for (long l : values) {
                    writeFixed64_0(l);
                }
            default:

        }
    }

    @Override
    public void writePackedLongs(byte[] fieldData, Iterable<Long> values, Field.Type type) {
        if (values == null || !values.iterator().hasNext()) {
            return;
        }
        int len;
        switch (type) {
            case INT64:
            case UINT64:
                len = 0;
                for (long l : values) {
                    len += computeRawVarint64Size(l);
                }
                expand(MAX_VARINT_SIZE << 1 + len);
                writeFieldData(fieldData);
                writeUInt32_0(len);
                for (long l : values) {
                    writeUInt64_0(l);
                }
                return;
            case SINT64:
                len = 0;
                for (long l : values) {
                    len += computeRawVarint64Size(encodeZigZag64(l));
                }
                expand(MAX_VARINT_SIZE << 1 + len);
                writeFieldData(fieldData);
                writeUInt32_0(len);
                for (long l : values) {
                    writeUInt64_0(encodeZigZag64(l));
                }
                return;
            case FIXED64:
            case SFIXED64:
                int size = 0;
                for (long l : values) {
                    size++;
                }
                expand(MAX_VARINT_SIZE << 1 + size << 3);
                writeFieldData(fieldData);
                writeUInt32_0(size << 3);
                for (long l : values) {
                    writeFixed64_0(l);
                }
            default:

        }
    }

    @Override
    public void writePackedDoubles(byte[] fieldData, double[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length << 3;
        expand(MAX_VARINT_SIZE << 1 + len);
        writeFieldData(fieldData);
        writeUInt32_0(len);
        for (double value : values) {
            writeFixed64_0(Double.doubleToLongBits(value));
        }
    }

    @Override
    public void writePackedDoubles(byte[] fieldData, Double[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length << 3;
        expand(MAX_VARINT_SIZE << 1 + len);
        writeFieldData(fieldData);
        writeUInt32_0(len);
        for (Double value : values) {
            double d = value==null?0:value.doubleValue();
            writeFixed64_0(Double.doubleToLongBits(d));
        }
    }

    @Override
    public void writePackedDoubles(byte[] fieldData, List<Double> values) {
        if (isEmpty(values)) {
            return;
        }
        int len = values.size() << 3;
        expand(MAX_VARINT_SIZE << 1 + len);
        writeFieldData(fieldData);
        writeUInt32_0(len);
        for (Double value : values) {
            writeFixed64_0(Double.doubleToLongBits(value));
        }
    }

    @Override
    public void writePackedDoubles(byte[] fieldData, Iterable<Double> values) {
        if (values == null || !values.iterator().hasNext()) {
            return;
        }
        int len = 0;
        for (Double value : values) {
            len++;
        }
        expand(MAX_VARINT_SIZE << 1 + len);
        writeFieldData(fieldData);
        writeUInt32_0(len);
        for (Double value : values) {
            writeFixed64_0(Double.doubleToLongBits(value));
        }
    }

    @Override
    public <E extends Enum<E>> void writeArrayEnum(byte[] fieldData, E[] values) {
        int size = values.length;
        int len = size * 5;
        expand((MAX_VARLONG_SIZE << 1) + len);
        writeFieldData(fieldData);
        int oldPos = pos;

        pos += 1;
        int i = 0;
        writeInt32_0(values[i++].ordinal());
        if (size > 1) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 2) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 3) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 4) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 5) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 6) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 7) {
            writeUInt32_0(values[i++].ordinal());
        }
        if (size > 8) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 9) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 10) {
            for (i=10;i<size;i++) {
                writeInt32_0(values[i].ordinal());
            }
        }
        len = pos - oldPos - 1;
        pos += writeLenMoveBytes(bs, oldPos, len);
    }

    @Override
    public void writeObject(byte[] fieldData, Object v) throws EncodeException {
        if (null == v) {
            return;
        }
        expand(MAX_VARINT_SIZE);
        writeFieldData(fieldData);
        AnyCodec.encode(this, v);
    }

    @Override
    public void writeObject(Object v) throws EncodeException {
        if (null == v) {
            return;
        }
        int len;
//        int oldPos = pos;
//        pos += 5;
        AnyCodec.encode(this, v);
    }

    @Override
    public <T> void writeMessage(T v, ProtoBufEncoder<T> codec) throws EncodeException {
        int oldPos = pos;
        pos += 1;
        codec.encode(this, v);
        int len = pos - oldPos - 1;
        pos += writeLenMoveBytes(bs, oldPos, len);
    }

    @Override
    public <T> void writeMessage(byte[] fieldData, int tag, T v, ProtoBufEncoder<T> codec) throws EncodeException {
        if (v == null) {
            return;
        }
        writeMessage0(fieldData, tag, v, codec);
    }

    public <T> void writeMessage0(byte[] fieldData, int tag, T v, ProtoBufEncoder<T> codec) throws EncodeException {
        int len;
        expand(MAX_VARINT_SIZE);
        writeFieldData(fieldData);
        int oldPos = pos;
        pos += 1;
        codec.encode(this, v);
        len = pos - oldPos - 1;
        pos += writeLenMoveBytes(bs, oldPos, len);
    }

    @Override
    public <T> void writeMessages(byte[] fieldData, int tag, T[] vs, ProtoBufEncoder<T> codec) throws EncodeException {
        if (vs == null) {
            return;
        }
        int size = vs.length;
        for (int i=0;i<size;i++) {
            //for (T v : vs) {
            T v = vs[i];
            writeMessage0(fieldData, tag, v, codec);
        }
    }

    @Override
    public <T> void writeMessages(byte[] fieldData, int tag, List<T> vs, ProtoBufEncoder<T> codec) throws EncodeException {
        if (CollectionUtils.isEmpty(vs)) {
            return;
        }
        int size = vs.size();
        int len;
        for (int i=0;i<size;i++) {
            //for (T v : vs) {
            T v = vs.get(i);
            writeMessage0(fieldData, tag, v, codec);
        }
    }

    @Override
    public <T> void writeMessages(byte[] fieldData, int tag, Iterable<T> vs, ProtoBufEncoder<T> codec) throws EncodeException {
        if (vs == null || !vs.iterator().hasNext()) {
            return;
        }
        for (T v : vs) {
            writeMessage0(fieldData, tag, v, codec);
        }
    }

}
