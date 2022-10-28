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
import io.edap.protobuf.wire.Field;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static io.edap.protobuf.ProtoBufWriter.encodeZigZag32;
import static io.edap.protobuf.ProtoBufWriter.encodeZigZag64;
import static io.edap.protobuf.wire.WireFormat.*;
import static io.edap.util.CollectionUtils.isEmpty;

public class StandardReverseWriter extends AbstractWriter {

    public StandardReverseWriter(BufOut out) {
        super(out);
    }

    @Override
    public WriteOrder getWriteOrder() {
        return WriteOrder.REVERSE;
    }

    @Override
    public void writeFieldData(final byte[] fieldData) {
        int len = fieldData.length;
        if (len == 1) {
            bs[--pos] = fieldData[0];
        } else if (len == 2) {
            bs[--pos] = fieldData[1];
            bs[--pos] = fieldData[0];
        } else {
            byte[] _bs = bs;
            int _p = pos;
            for (int i = len - 1; i >= 0; i--) {
                _bs[--_p] = fieldData[i];
            }
            pos = _p;
        }

    }

    @Override
    public void writeBool(final byte[] fieldData, final boolean value) {
        if (!value) {
            return;
        }
        expand(fieldData.length + 1);
        bs[--pos] = (byte) (value ? 1 : 0);
        writeFieldData(fieldData);
    }

    @Override
    public void writeByteArray(final byte[] fieldData, final byte[] value,
                               int offset, int length) {


        expand(length + MAX_VARINT_SIZE * 2);
        pos -= length;
        writeByteArray_0(value, offset, length);
        writeUInt32_0(length);
        writeFieldData(fieldData);
    }

    @Override
    public void writeByteArray(final byte[] value, int offset, int length) {


        expand(length + MAX_VARINT_SIZE);
        pos -= length;
        writeByteArray_0(value, offset, length);
        writeUInt32_0(length);
    }

    @Override
    public final void writeByte(final byte b) {
        expand(1);
        this.bs[--pos] = b;
    }

    @Override
    public void writeBytes(final byte[] bs) {
        if (bs == null || bs.length == 0) {
            return;
        }

        int len = bs.length;
        pos -= len;
        writeByteArray_0(bs, 0, len);
    }

    /**
     * 不检查空间是否够用直接写uint32数据
     * @param value
     */
    @Override
    protected void writeUInt32_0(int value) {
        if (value < 0) {
            writeUInt64_0(value);
            return;
        }
        /**/
        byte[] _bs = this.bs;
        int p = pos;
        if ((value & ~0x7F) == 0) {
            _bs[--p] = (byte) value;
        } else {
            if ((value >>> 28 & 0x7F) != 0) {
                _bs[--p] = (byte) (value >>> 28);
                _bs[--p] = (byte) ((value >>> 21) | 0x80);
                _bs[--p] = (byte) ((value >>> 14) | 0x80);
                _bs[--p] = (byte) ((value >>>  7) | 0x80);
                _bs[--p] = (byte) (value         | 0x80);
            } else if ((value >>> 21 & 0x7F) != 0) {
                _bs[--p] = (byte) (value >>> 21);
                _bs[--p] = (byte) ((value >>> 14) | 0x80);
                _bs[--p] = (byte) ((value >>>  7) | 0x80);
                _bs[--p] = (byte) (value         | 0x80);
            } else if ((value >>> 14 & 0x7F) != 0) {
                _bs[--p] = (byte) (value >>> 14);
                _bs[--p] = (byte) ((value >>>  7) | 0x80);
                _bs[--p] = (byte) (value         | 0x80);
            } else if ((value >>> 7 & 0x7F) != 0) {
                _bs[--p] = (byte) (value >>> 7);
                _bs[--p] = (byte) (value         | 0x80);
            }
        }
        pos = p;
    }

    @Override
    public void writeFixed64(final byte[] fieldData, final long value) {
        if (0 == value) {
            return;
        }
        expand(MAX_VARINT_SIZE + FIXED_64_SIZE);
        writeFixed64_0(value);
        writeFieldData(fieldData);
    }

    @Override
    public void writeFixed64(long value) {
        expand(FIXED_64_SIZE);
        writeFixed64_0(value);
    }

    @Override
    protected void writeByteArray_0(final byte[] value, int offset, int length) {
        System.arraycopy(value, offset, bs, pos, length);
    }

    @Override
    public void writeUInt32(final byte[] fieldData, final int value) {
        if (0 == value) {
            return;
        }
        expand(MAX_VARLONG_SIZE);
        writeUInt32_0(value);
        writeFieldData(fieldData);
    }

    @Override
    public void writeFixed32(final byte[] fieldData, final int value) {
        if (0 == value) {
            return;
        }
        expand(fieldData.length + FIXED_32_SIZE);
        writeFixed32_0(value);
        writeFieldData(fieldData);
    }

    @Override
    public void writeFixed32(final int value) {
        expand(FIXED_32_SIZE);
        writeFixed32_0(value);
    }

    @Override
    public void writeUInt64(final byte[] fieldData, final long value) {
        if (value == 0) {
            return;
        }
        expand(MAX_VARINT_SIZE + MAX_VARLONG_SIZE);
        writeUInt64_0(value);
        writeFieldData(fieldData);
    }

    @Override
    public void writeUInt64(long value) {
        expand(MAX_VARLONG_SIZE);
        writeUInt64_0(value);
    }

    @Override
    protected void writeUInt64_0(long value) {
        byte[] _bs = this.bs;
        int p = pos;
        if ((value & ~0x7FL) == 0) {
            _bs[--p] = (byte) value;
        } else {
            if ((value >>> 63 & 0x7FL) != 0) {
                _bs[--p] = (byte) (value >>> 63);
                _bs[--p] = (byte) ((int)(value >>> 56) | 0x80);
                _bs[--p] = (byte) ((int)(value >>> 49) | 0x80);
                _bs[--p] = (byte) ((int)(value >>> 42) | 0x80);
                _bs[--p] = (byte) ((int)(value >>> 35) | 0x80);
                _bs[--p] = (byte) ((int)(value >>> 28) | 0x80);
                _bs[--p] = (byte) ((int)(value >>> 21) | 0x80);
                _bs[--p] = (byte) ((int)(value >>> 14) | 0x80);
                _bs[--p] = (byte) ((int)(value >>>  7) | 0x80);
                _bs[--p] = (byte) ((int)value         | 0x80);
            } else if ((value >> 56 & 0x7FL) != 0) {
                _bs[--p] = (byte) (value >>> 56);
                _bs[--p] = (byte) ((value >>> 49) | 0x80);
                _bs[--p] = (byte) ((value >>> 42) | 0x80);
                _bs[--p] = (byte) ((value >>> 35) | 0x80);
                _bs[--p] = (byte) ((value >>> 28) | 0x80);
                _bs[--p] = (byte) ((value >>> 21) | 0x80);
                _bs[--p] = (byte) ((value >>> 14) | 0x80);
                _bs[--p] = (byte) ((value >>>  7) | 0x80);
                _bs[--p] = (byte) (value         | 0x80);
            } else if ((value >> 49 & 0x7FL) != 0) {
                _bs[--p] = (byte) (value >>> 49);
                _bs[--p] = (byte) ((value >>> 42) | 0x80);
                _bs[--p] = (byte) ((value >>> 35) | 0x80);
                _bs[--p] = (byte) ((value >>> 28) | 0x80);
                _bs[--p] = (byte) ((value >>> 21) | 0x80);
                _bs[--p] = (byte) ((value >>> 14) | 0x80);
                _bs[--p] = (byte) ((value >>>  7) | 0x80);
                _bs[--p] = (byte) (value         | 0x80);
            } else if ((value >> 42 & 0x7FL) != 0) {
                _bs[--p] = (byte) (value >>> 42);
                _bs[--p] = (byte) ((value >>> 35) | 0x80);
                _bs[--p] = (byte) ((value >>> 28) | 0x80);
                _bs[--p] = (byte) ((value >>> 21) | 0x80);
                _bs[--p] = (byte) ((value >>> 14) | 0x80);
                _bs[--p] = (byte) ((value >>>  7) | 0x80);
                _bs[--p] = (byte) (value         | 0x80);
            } else if ((value >> 35 & 0x7FL) != 0) {
                _bs[--p] = (byte) (value >>> 35);
                _bs[--p] = (byte) ((value >>> 28) | 0x80);
                _bs[--p] = (byte) ((value >>> 21) | 0x80);
                _bs[--p] = (byte) ((value >>> 14) | 0x80);
                _bs[--p] = (byte) ((value >>>  7) | 0x80);
                _bs[--p] = (byte) (value         | 0x80);
            } else if ((value >> 28 & 0x7FL) != 0) {
                _bs[--p] = (byte) (value >>> 28);
                _bs[--p] = (byte) ((value >>> 21) | 0x80);
                _bs[--p] = (byte) ((value >>> 14) | 0x80);
                _bs[--p] = (byte) ((value >>>  7) | 0x80);
                _bs[--p] = (byte) (value         | 0x80);
            } else if ((value >> 21 & 0x7FL) != 0) {
                _bs[--p] = (byte) (value >>> 21);
                _bs[--p] = (byte) ((value >>> 14) | 0x80);
                _bs[--p] = (byte) ((value >>>  7) | 0x80);
                _bs[--p] = (byte) (value         | 0x80);
            } else if ((value >> 14 & 0x7FL) != 0) {
                _bs[--p] = (byte) (value >>> 14);
                _bs[--p] = (byte) ((value >>>  7) | 0x80);
                _bs[--p] = (byte) (value         | 0x80);
            } else if ((value >> 7 & 0x7FL) != 0) {
                _bs[--p] = (byte) (value >>> 7);
                _bs[--p] = (byte) (value         | 0x80);
            }

        }
        pos = p;
    }

    @Override
    public void writeSInt32(final byte[] fieldData, final int value) {
        if (0 == value) {
            return;
        }
        expand(MAX_VARLONG_SIZE);
        writeUInt32_0(ProtoBufWriter.encodeZigZag32(value));
        writeFieldData(fieldData);
    }

    @Override
    public void writeInt32(final byte[] fieldData, final int value) {
        if (0 == value) {
            return;
        }
        if (value > 0) {
            expand(MAX_VARLONG_SIZE);
            writeUInt32_0(value);
            writeFieldData(fieldData);

        } else {
            expand(MAX_VARLONG_SIZE + MAX_VARINT_SIZE);
            writeUInt64_0(value);
            writeByteArray_0(fieldData, 0, fieldData.length);
        }
    }

    @Override
    public <E extends Enum<E>> void writeArrayEnum(byte[] fieldData, E[] values) {
        if (CollectionUtils.isEmpty(values)) {
            return;
        }
        int size = values.length;
        expand(size * 5 + 10);
        int oldPos = pos;

        int i;
        if (size > 10) {
            for (i=size-1;i>0;i--) {
                writeInt32_0(values[i].ordinal());
            }
        } else {
            if (size > 9) {
                writeInt32_0(values[9].ordinal());
            }
            if (size > 8) {
                writeInt32_0(values[8].ordinal());
            }
            if (size > 7) {
                writeInt32_0(values[6].ordinal());
            }
            if (size > 6) {
                writeInt32_0(values[6].ordinal());
            }
            if (size > 5) {
                writeInt32_0(values[5].ordinal());
            }
            if (size > 4) {
                writeInt32_0(values[4].ordinal());
            }
            if (size > 3) {
                writeInt32_0(values[3].ordinal());
            }
            if (size > 2) {
                writeInt32_0(values[2].ordinal());
            }
            if (size > 1) {
                writeInt32_0(values[1].ordinal());
            }
            writeInt32_0(values[0].ordinal());
        }

        writeUInt32_0(oldPos - pos);
        writeFieldData(fieldData);
    }

    @Override
    public void writePackedInts(byte[] fieldData, int[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int size = values.length;
        int len;
        int i;
        switch (type) {
            case INT32:
            case UINT32:
                size = values.length;
                expand((MAX_VARLONG_SIZE << 1) + size * MAX_VARINT_SIZE);
                int oldPos = pos;
                if (size > 10) {
                    for (i=size-1;i>0;i--) {
                        writeInt32_0(values[i]);
                    }
                } else {
                    if (size > 9) {
                        writeInt32_0(values[9]);
                    }
                    if (size > 8) {
                        writeInt32_0(values[8]);
                    }
                    if (size > 7) {
                        writeInt32_0(values[6]);
                    }
                    if (size > 6) {
                        writeInt32_0(values[6]);
                    }
                    if (size > 5) {
                        writeInt32_0(values[5]);
                    }
                    if (size > 4) {
                        writeInt32_0(values[4]);
                    }
                    if (size > 3) {
                        writeInt32_0(values[3]);
                    }
                    if (size > 2) {
                        writeInt32_0(values[2]);
                    }
                    if (size > 1) {
                        writeInt32_0(values[1]);
                    }
                    writeInt32_0(values[0]);
                }

                len = oldPos - pos;
                writeUInt32_0(len);
                writeFieldData(fieldData);
                return;
            case SINT32:
                expand(MAX_VARINT_SIZE << 1 + size * 5);
                oldPos = pos;
                for (i=size-1;i>=0;i--) {
                    writeUInt32_0(encodeZigZag32(values[i]));
                }
                writeUInt32_0(oldPos - pos);
                writeFieldData(fieldData);
                return;
            case FIXED32:
            case SFIXED32:
                expand(MAX_VARINT_SIZE << 1 + size << 2);
                for (i=size-1;i>=0;i--) {
                    writeFixed32_0(values[i]);
                }
                writeUInt32_0(size << 2);
                writeFieldData(fieldData);
            default:
                break;
        }
    }

    @Override
    public void writePackedInts(byte[] fieldData, List<Integer> values, Field.Type type) {
        if (CollectionUtils.isEmpty(values)) {
            return;
        }
        int size = values.size();
        int len;
        int i;
        switch (type) {
            case INT32:
            case UINT32:
                expand((MAX_VARLONG_SIZE << 1) + size * MAX_VARINT_SIZE);
                int oldPos = pos;
                if (size > 10) {
                    for (i=size-1;i>0;i--) {
                        writeInt32_0(values.get(i));
                    }
                } else {
                    if (size > 9) {
                        writeInt32_0(values.get(9));
                    }
                    if (size > 8) {
                        writeInt32_0(values.get(8));
                    }
                    if (size > 7) {
                        writeInt32_0(values.get(7));
                    }
                    if (size > 6) {
                        writeInt32_0(values.get(6));
                    }
                    if (size > 5) {
                        writeInt32_0(values.get(5));
                    }
                    if (size > 4) {
                        writeInt32_0(values.get(4));
                    }
                    if (size > 3) {
                        writeInt32_0(values.get(3));
                    }
                    if (size > 2) {
                        writeInt32_0(values.get(2));
                    }
                    if (size > 1) {
                        writeInt32_0(values.get(1));
                    }
                    writeInt32_0(values.get(0));
                }

                len = oldPos - pos;
                writeUInt32_0(len);
                writeFieldData(fieldData);
                return;
            case SINT32:
                expand((MAX_VARINT_SIZE << 1) + size * 5);
                oldPos = pos;
                for (i=size-1;i>=0;i--) {
                    writeUInt32_0(encodeZigZag32(values.get(i)));
                }
                writeUInt32_0(oldPos - pos);
                writeFieldData(fieldData);
                return;
            case FIXED32:
            case SFIXED32:
                expand((MAX_VARINT_SIZE << 1) + (size << 2));
                for (i=size-1;i>=0;i--) {
                    writeFixed32_0(values.get(i));
                }
                writeUInt32_0(size << 2);
                writeFieldData(fieldData);
            default:
                break;
        }
    }

    @Override
    public void writePackedInts(byte[] fieldData, Iterable<Integer> values, Field.Type type) {
        if (CollectionUtils.isEmpty(values)) {
            return;
        }
        int len;
        int i;
        i = 0;
        List<Integer> vs = new ArrayList<>();
        for (Integer v : vs) {
            vs.add(v);
        }
        int size = vs.size();
        switch (type) {
            case INT32:
            case UINT32:
                expand((MAX_VARLONG_SIZE << 1) + size * MAX_VARINT_SIZE);
                int oldPos = pos;
                for (i=size-1;i>=0;i--) {
                    writeFixed32_0(vs.get(i));
                }
                len = oldPos - pos;
                writeUInt32_0(len);
                writeFieldData(fieldData);
                return;
            case SINT32:
                expand((MAX_VARINT_SIZE << 1) + size * 5);
                oldPos = pos;
                for (i=size-1;i>=0;i--) {
                    writeUInt32_0(encodeZigZag32(vs.get(i)));
                }
                writeUInt32_0(oldPos - pos);
                writeFieldData(fieldData);
                return;
            case FIXED32:
            case SFIXED32:
                expand((MAX_VARINT_SIZE << 1) + (size << 2));
                for (i=size-1;i>=0;i--) {
                    writeFixed32_0(vs.get(i));
                }
                writeUInt32_0(size << 2);
                writeFieldData(fieldData);
            default:
                break;
        }
    }

    @Override
    public void writePackedInts(byte[] fieldData, Integer[] values, Field.Type type) {
        if (CollectionUtils.isEmpty(values)) {
            return;
        }
        int size = values.length;
        int oldPos;
        int i;
        switch (type) {
            case INT32:
            case UINT32:
                expand((MAX_VARLONG_SIZE << 1) + size * 5);
                oldPos = pos;
                if (size > 10) {
                    for (i=size-1;i>=0;i--) {
                        writeInt32_0(values[i]);
                    }
                } else {
                    if (size > 9) {
                        writeInt32_0(values[9]);
                    }
                    if (size > 8) {
                        writeInt32_0(values[8]);
                    }
                    if (size > 7) {
                        writeInt32_0(values[7]);
                    }
                    if (size > 6) {
                        writeInt32_0(values[6]);
                    }
                    if (size > 5) {
                        writeInt32_0(values[5]);
                    }
                    if (size > 4) {
                        writeInt32_0(values[4]);
                    }
                    if (size > 3) {
                        writeInt32_0(values[3]);
                    }
                    if (size > 2) {
                        writeInt32_0(values[2]);
                    }
                    if (size > 1) {
                        writeInt32_0(values[1]);
                    }
                    writeInt32_0(values[0]);
                }
                writeUInt32_0(oldPos - pos);
                writeFieldData(fieldData);
                return;
            case SINT32:
                expand((MAX_VARINT_SIZE << 1) + (size * 5));
                oldPos = pos;
                for (i=size-1;i>=0;i--) {
                    writeUInt32_0(encodeZigZag32(values[i]));
                }
                writeUInt32_0(oldPos - pos);
                writeFieldData(fieldData);
                return;
            case FIXED32:
            case SFIXED32:
                expand((MAX_VARINT_SIZE << 1) + (size << 2));
                for (i=size-1;i>=0;i--) {
                    writeFixed32_0(values[i]);
                }
                writeUInt32_0(size << 2);
                writeFieldData(fieldData);
            default:
                break;
        }
    }

    @Override
    public void writePackedDoubles(byte[] fieldData, List<Double> values) {
        if (isEmpty(values)) {
            return;
        }
        int len = values.size() << 3;
        expand((MAX_VARINT_SIZE << 1) + len);
        for (int i=values.size()-1;i>=0;i--) {
            writeFixed64_0(Double.doubleToLongBits(values.get(i)));
        }
        writeUInt32_0(len);
        writeFieldData(fieldData);
    }

    @Override
    public void writePackedDoubles(byte[] fieldData, Iterable<Double> values) {
        if (isEmpty(values)) {
            return;
        }
        List<Double> vs = new ArrayList<>();
        for (Double v : values) {
            vs.add(v);
        }
        int len = vs.size() << 3;
        expand((MAX_VARINT_SIZE << 1) + len);
        for (int i=vs.size()-1;i>=0;i--) {
            writeFixed64_0(Double.doubleToLongBits(vs.get(i)));
        }
        writeUInt32_0(len);
        writeFieldData(fieldData);
    }

    @Override
    public void writePackedLongs(byte[] fieldData, List<Long> values, Field.Type type) {
        if (isEmpty(values)) {
            return;
        }
        int oldPos;
        int i;
        switch (type) {
            case INT64:
            case UINT64:
                expand((MAX_VARINT_SIZE << 1) + values.size()*10);
                oldPos = pos;
                for (i = values.size()-1;i>=0;i--) {
                    writeUInt64_0(values.get(i));
                }
                writeUInt32_0(oldPos - pos);
                writeFieldData(fieldData);
                return;
            case SINT64:
                expand((MAX_VARINT_SIZE << 1) + values.size() * 10);
                oldPos = pos;
                for (i = values.size()-1;i>=0;i--) {
                    writeUInt64_0(encodeZigZag64(values.get(i)));
                }
                writeUInt32_0(oldPos - pos);
                writeFieldData(fieldData);
                return;
            case FIXED64:
            case SFIXED64:
                int size = values.size();
                expand(MAX_VARINT_SIZE << 1 + size << 3);
                for (i = values.size()-1;i>=0;i--) {
                    writeFixed64_0(values.get(i));
                }
                writeUInt32_0(size << 3);
                writeFieldData(fieldData);
            default:

        }
    }

    @Override
    public void writePackedLongs(byte[] fieldData, Iterable<Long> values, Field.Type type) {
        if (isEmpty(values)) {
            return;
        }
        int oldPos;
        List<Long> vs = new ArrayList<>();
        for (Long v : values) {
            vs.add(v);
        }
        int i;
        switch (type) {
            case INT64:
            case UINT64:
                expand((MAX_VARINT_SIZE << 1) + vs.size()*10);
                oldPos = pos;
                for (i = vs.size()-1;i>=0;i--) {
                    writeUInt64_0(vs.get(i));
                }
                writeUInt32_0(oldPos - pos);
                writeFieldData(fieldData);
                return;
            case SINT64:
                expand((MAX_VARINT_SIZE << 1) + vs.size() * 10);
                oldPos = pos;
                for (i = vs.size()-1;i>=0;i--) {
                    writeUInt64_0(encodeZigZag64(vs.get(i)));
                }
                writeUInt32_0(oldPos - pos);
                writeFieldData(fieldData);
                return;
            case FIXED64:
            case SFIXED64:
                int size = vs.size();
                expand(MAX_VARINT_SIZE << 1 + size << 3);
                for (i = size-1;i>=0;i--) {
                    writeFixed64_0(vs.get(i));
                }
                writeUInt32_0(size << 3);
                writeFieldData(fieldData);
            default:

        }
    }

    @Override
    public void writePackedLongs(byte[] fieldData, Long[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int oldPos;
        int size = values.length;
        switch (type) {
            case INT64:
            case UINT64:
                expand((MAX_VARINT_SIZE << 1) + size * 10);
                oldPos = pos;
                for (int i=size-1;i>=0;i--) {
                    writeUInt64_0(values[i]);
                }
                writeUInt32_0(oldPos - pos);
                writeFieldData(fieldData);
                return;
            case SINT64:
                expand((MAX_VARINT_SIZE << 1) + size * 10);
                oldPos = pos;
                for (int i=size-1;i>=0;i--) {
                    writeUInt64_0(encodeZigZag64(values[i]));
                }
                writeUInt32_0(oldPos - pos);
                writeFieldData(fieldData);
                return;
            case FIXED64:
            case SFIXED64:
                expand((MAX_VARINT_SIZE << 1) + size << 3);
                for (int i=size-1;i>=0;i--) {
                    writeFixed64_0(values[i]);
                }
                writeUInt32_0(size << 3);
                writeFieldData(fieldData);
            default:

        }
    }

    @Override
    public void writePackedLongs(byte[] fieldData, long[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int oldPos;
        int size = values.length;
        switch (type) {
            case INT64:
            case UINT64:
                expand((MAX_VARINT_SIZE << 1) + size * 10);
                oldPos = pos;
                for (int i = size - 1 ;i >= 0; i--) {
                    writeUInt64_0(values[i]);
                }
                writeUInt32_0(oldPos - pos);
                writeFieldData(fieldData);
                return;
            case SINT64:
                expand((MAX_VARINT_SIZE << 1) + size * 10);
                oldPos = pos;
                for (int i=size-1;i >= 0 ;i--) {
                    writeUInt64_0(encodeZigZag64(values[i]));
                }
                writeUInt32_0(oldPos - pos);
                writeFieldData(fieldData);
                return;
            case FIXED64:
            case SFIXED64:
                expand((MAX_VARINT_SIZE << 1) + (size << 3));
                for (int i=size-1;i>=0;i--) {
                    writeFixed64_0(values[i]);
                }
                writeUInt32_0(size << 3);
                writeFieldData(fieldData);
            default:

        }
    }

    @Override
    public void writePackedFloats(byte[] fieldData, List<Float> values) {
        if (isEmpty(values)) {
            return;
        }
        int len = values.size() << 2;
        expand( MAX_VARINT_SIZE << 1 + len);
        for (int i=values.size()-1;i>=0;i--) {
            writeFixed32_0(Float.floatToRawIntBits(values.get(i)));
        }
        writeUInt32(len);
        writeFieldData(fieldData);
    }

    @Override
    public void writePackedFloats(byte[] fieldData, Iterable<Float> values) {
        if (isEmpty(values)) {
            return;
        }
        List<Float> vs = new ArrayList<>();
        int i = 0;
        for (Float v : values) {
            vs.add(v);
        }
        int len = vs.size() << 2;
        expand( MAX_VARINT_SIZE << 1 + len);
        for (i=vs.size()-1;i>=0;i--) {
            writeFixed32_0(Float.floatToRawIntBits(vs.get(i)));
        }
        writeUInt32(len);
        writeFieldData(fieldData);
    }

    @Override
    public void writePackedFloats(byte[] fieldData, float[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length << 2;
        expand(MAX_VARINT_SIZE << 1 + len);
        for (int i=values.length-1;i>=0;i--) {
            writeFixed32_0(Float.floatToRawIntBits(values[i]));
        }
        writeUInt32_0(len);
        writeFieldData(fieldData);
    }

    @Override
    public void writePackedFloats(byte[] fieldData, Float[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length << 2;
        expand(MAX_VARINT_SIZE << 1 + len);
        for (int i=values.length-1;i>=0;i--) {
            writeFixed32_0(Float.floatToRawIntBits(values[i]==null?0:values[i].floatValue()));
        }
        writeUInt32_0(len);
        writeFieldData(fieldData);
    }

    @Override
    public void writePackedBooleans(byte[] fieldData, boolean[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        expand(MAX_VARINT_SIZE + values.length);
        for (int i=values.length-1;i>=0;i--) {
            Boolean value = values[i];
            writeUInt32_0(!value.booleanValue()?0:1);
        }
        writeUInt32(values.length);
        writeFieldData(fieldData);
    }

    @Override
    public void writePackedBooleans(byte[] fieldData, Boolean[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        expand(MAX_VARINT_SIZE + values.length);
        for (int i=values.length-1;i>=0;i--) {
            Boolean value = values[i];
            writeUInt32_0(value==null||!value.booleanValue()?0:1);
        }
        writeUInt32(values.length);
        writeFieldData(fieldData);
    }

    @Override
    public void writePackedBooleans(byte[] fieldData, List<Boolean> values) {
        if (values == null || values.size() == 0) {
            return;
        }
        Boolean[] vs = new Boolean[values.size()];
        int i = 0;
        for (Boolean v : values) {
            vs[i++] = v;
        }
        writePackedBooleans(fieldData, vs);
    }

    @Override
    public void writePackedBooleans(byte[] fieldData, Iterable<Boolean> values) {
        if (values == null || !values.iterator().hasNext()) {
            return;
        }
        List<Integer> vs = new ArrayList<>();
        for (Boolean v : values) {
            vs.add(v!=null||v.booleanValue()?1:0);
        }
        writePackedInts(fieldData, vs, Field.Type.INT32);
    }

    @Override
    public void writePackedDoubles(byte[] fieldData, double[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length << 3;
        expand(MAX_VARINT_SIZE << 1 + len);
        for (int i=values.length-1;i>=0;i--) {
            writeFixed64_0(Double.doubleToLongBits(values[i]));
        }
        writeUInt32_0(len);
        writeFieldData(fieldData);
    }

    @Override
    public void writePackedDoubles(byte[] fieldData, Double[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length << 3;
        expand(MAX_VARINT_SIZE << 1 + len);
        for (int i=values.length-1;i>=0;i--) {
            Double value = values[i];
            double d = value==null?0:value.doubleValue();
            writeFixed64_0(Double.doubleToLongBits(d));
        }
        writeUInt32_0(len);
        writeFieldData(fieldData);
    }

    @Override
    public final void writeString(final byte[] fieldData, final String value) {
        if (StringUtil.isEmpty(value)) {
            return;
        }
        writeString(value);
        expand(fieldData.length);
        writeFieldData(fieldData);
    }

    @Override
    public void writeStringUtf8(final String value, int len) {
        int charLen = value.length();

        /**/
        if (len == -1) {
            expand(charLen * 3);
        } else {
            expand(len);
        }
        int p = pos;
        byte[] bs = this.bs;
        int i;
        for (i=charLen-1;i>=0;i--) {
            char c = value.charAt(i);
//            if (ch < 128) {
//                bs[--p] = (byte)ch;
//            } else if (ch < 2048) {
//                bs[--p] = (byte)(192 + (ch >> 6 & 31));
//                bs[--p] = (byte)(128 + (ch & 63));
//            } else {
//                bs[--p] = (byte)(224 + (ch >> 12 & 15));
//                bs[--p] = (byte)(128 + (ch >> 6 & 63));
//                bs[--p] = (byte)(128 + (ch & 63));
//            }
            if (c < 128) {
                bs[--p] = (byte) c;
            } else {
                break;
            }
        }
        if (i >= 0) {
            for (; i >= 0; i--) {
                char c = value.charAt(i);
                if (c < 128) {
                    bs[--p] = (byte) c;
                } else if (c < 0x800) {
                    bs[--p] = (byte) (0x80 | (0x3F & c));
                    bs[--p] = (byte) ((0xF << 6) | (c >>> 6));

                } else if (Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(value.charAt(i - 1))) {
                    int codePoint = Character.toCodePoint((char) value.charAt(i - 1), c);
                    bs[--p] = (byte) (0x80 | (codePoint & 0x3F));
                    bs[--p] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
                    bs[--p] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                    bs[--p] = (byte) (0xF0 | ((codePoint >> 18) & 0x07));
                    i--;
                } else {
                    bs[--p] = (byte) (0x80 | (0x3F & c));
                    bs[--p] = (byte) (0x80 | (0x3F & (c >>> 6)));
                    bs[--p] = (byte) ((0xF << 5) | (c >>> 12));
                }
            }
        }
        pos = p;
    }

    @Override
    public void writeString(final String value) {
        if (value == null) {
            expand(10);
            writeUInt32_0(-1);
            return;
        }
        int charLen = value.length();
        if (charLen == 0) {
            expand(1);
            writeUInt32_0(0);
            return;
        }


        //int start = charLen;
        /**/
        expand(charLen * 3 + MAX_VARINT_SIZE);
        int oldPos = pos;
        int p = pos;
        byte[] bs = this.bs;
        int i = charLen - 1;
        for (;i>=0;i--) {
            char c = value.charAt(i);
            if (c < 128) {
                bs[--p] = (byte) c;
            } else {
                break;
            }
        }
        if (i >= 0) {
            for (; i >= 0; i--) {
                char c = value.charAt(i);
                if (c < 128) {
                    bs[--p] = (byte) c;
                } else if (c < 0x800) {
                    bs[--p] = (byte) (0x80 | (0x3F & c));
                    bs[--p] = (byte) ((0xF << 6) | (c >>> 6));

                } else if (Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(value.charAt(i - 1))) {
                    int codePoint = Character.toCodePoint((char) value.charAt(i - 1), c);
                    bs[--p] = (byte) (0x80 | (codePoint & 0x3F));
                    bs[--p] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
                    bs[--p] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                    bs[--p] = (byte) (0xF0 | ((codePoint >> 18) & 0x07));
                    i--;
                } else {
                    bs[--p] = (byte) (0x80 | (0x3F & c));
                    bs[--p] = (byte) (0x80 | (0x3F & (c >>> 6)));
                    bs[--p] = (byte) ((0xF << 5) | (c >>> 12));
                }
            }
        }
        pos = p;

        writeUInt32_0(oldPos - p);
    }

    protected final void writeFixed32_0(int value) {
        bs[--pos] = (byte) ((value >> 24) & 0xFF);
        bs[--pos] = (byte) ((value >> 16) & 0xFF);
        bs[--pos] = (byte) ((value >>  8) & 0xFF);
        bs[--pos] = (byte) ((value      ) & 0xFF);
    }

    protected final void writeFixed64_0(long value) {
        bs[--pos] = ((byte) ((int) (value >> 56) & 0xFF));
        bs[--pos] = ((byte) ((int) (value >> 48) & 0xFF));
        bs[--pos] = ((byte) ((int) (value >> 40) & 0xFF));
        bs[--pos] = ((byte) ((int) (value >> 32) & 0xFF));
        bs[--pos] = ((byte) ((int) (value >> 24) & 0xFF));
        bs[--pos] = ((byte) ((int) (value >> 16) & 0xFF));
        bs[--pos] = ((byte) ((int) (value >>  8) & 0xFF));
        bs[--pos] = ((byte) ((int) (value      ) & 0xFF));
    }

    @Override
    public <T> void writeMessage(byte[] fieldData, int tag, T v, ProtoBufEncoder<T> encoder) throws EncodeException {
        if (v == null) {
            return;
        }
        writeMessage0(fieldData, tag, v, encoder);
    }

    public <T> void writeMessage0(byte[] fieldData, int tag, T v, ProtoBufEncoder<T> encoder)
            throws EncodeException {
        int oldPos = pos;
        encoder.encode(this, v);
        expand(MAX_VARINT_SIZE * 2);
        writeUInt32_0(oldPos - pos);
        writeFieldData(fieldData);
    }

    @Override
    public void writeObject(byte[] fieldData, Object v)  throws EncodeException {
        if (null == v) {
            return;
        }
        int oldPos = pos;
        AnyCodec.encode(this, v);
        expand(MAX_VARINT_SIZE * 2);
        //writeUInt32_0(oldPos - pos);
        writeFieldData(fieldData);
    }

    @Override
    public void writeObject(Object v)  throws EncodeException {
        if (null == v) {
            return;
        }
        int oldPos = pos;
        AnyCodec.encode(this, v);
        //writeUInt32_0(oldPos - pos);
    }

    @Override
    public <T> void writeMessage(T v, ProtoBufEncoder<T> encoder)
            throws EncodeException {
        int oldPos = pos;
        encoder.encode(this, v);
        expand(MAX_VARINT_SIZE * 2);
        writeUInt32_0(oldPos - pos);
    }

    @Override
    public <T> void writeMessages(byte[] fieldData, int tag, T[] msg, ProtoBufEncoder<T> encoder) throws EncodeException {
        if (CollectionUtils.isEmpty(msg)) {
            return;
        }
        int oldPos;
        for (int i = msg.length - 1; i>= 0;i--) {
            //writeMessage0(fieldData, tag, msg[i], encoder);
            oldPos = pos;
            encoder.encode(this, msg[i]);
            expand(MAX_VARINT_SIZE * 2);
            writeUInt32_0(oldPos - pos);
            writeFieldData(fieldData);
        }
    }

    @Override
    public <T> void writeMessages(byte[] fieldData, int tag, List<T> msg, ProtoBufEncoder<T> encoder) throws EncodeException {
        if (CollectionUtils.isEmpty(msg)) {
            return;
        }
        int t = msg.size() - 1;
        for (int i = t; i>= 0;i--) {
            writeMessage0(fieldData, tag, msg.get(i), encoder);
        }
    }

    @Override
    public <T> void writeMessages(byte[] fieldData, int tag, Iterable<T> msg, ProtoBufEncoder<T> encoder) throws EncodeException {
        if (msg == null || !msg.iterator().hasNext()) {
            return;
        }
        List<T> msgs = new ArrayList<>();
        for (T v : msg) {
            msgs.add(v);
        }
        writeMessages(fieldData, tag, msgs, encoder);
    }

    @Override
    public void expand(int minLength) {
        if (pos < minLength) {
            if (wbuf.out.hasBuf()) {
                wbuf.out.write(wbuf.bs, 0, wbuf.start);
                wbuf.writeLen += wbuf.start;
                wbuf.start = 0;
            } else {
                int len = wbuf.len * 2;
                if (len < minLength + pos) {
                    len = minLength + pos;
                }
                byte[] res = new byte[len];
                System.arraycopy(bs, pos, res, len - pos, wbuf.len - pos);
                pos += len - wbuf.bs.length;

                wbuf.bs = res;
                wbuf.out.setLocalBytes(res);
                bs = res;
            }
        }
    }

    @Override
    public void reset() {
        pos = wbuf.len;
    }

    @Override
    public byte[] toByteArray() {
        int len = bs.length - pos;
        byte[] data = new byte[len];
        System.arraycopy(bs, pos, data, 0, len);
        return data;
    }
}
