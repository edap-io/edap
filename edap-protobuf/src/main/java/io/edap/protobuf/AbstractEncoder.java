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

import io.edap.protobuf.wire.Field;
import io.edap.protobuf.wire.Field.Type;

import static io.edap.protobuf.util.ProtoUtil.computeRawVarint32Size;
import static io.edap.protobuf.wire.WireFormat.FIXED_32_SIZE;
import static io.edap.protobuf.wire.WireFormat.FIXED_64_SIZE;
import static io.edap.util.CollectionUtils.isEmpty;

/**
 * 将常用的编码的功能抽到该编码器中，减少asm字节码生成的逻辑
 * @author : louis
 * @date : 2019/12/24
 */
public abstract class AbstractEncoder {

    protected void writeArrayFloat(ProtoBufWriter writer, byte[] fieldData, float[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length * FIXED_32_SIZE;
        writer.writeByteArray(fieldData, 0, fieldData.length);
        writer.writeUInt32(len);
        for (float v : values) {
            writer.writeFixed32(Float.floatToRawIntBits(v));
        }
    }

    protected void writeArrayFloat(ProtoBufWriter writer, byte[] fieldData, Float[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length * FIXED_32_SIZE;
        writer.writeByteArray(fieldData, 0, fieldData.length);
        writer.writeUInt32(len);
        for (float v : values) {
            writer.writeFixed32(Float.floatToRawIntBits(v));
        }
    }

    protected void writeArrayDouble(ProtoBufWriter writer, byte[] fieldData, double[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length * FIXED_64_SIZE;
        writer.writeByteArray(fieldData, 0, fieldData.length);
        writer.writeUInt32(len);
        for (double v : values) {
            writer.writeFixed64(Double.doubleToRawLongBits(v));
        }
    }

    protected void writeArrayDouble(ProtoBufWriter writer, byte[] fieldData, Double[] values) {
        if (isEmpty(values)) {
            return;
        }
        int len = values.length * FIXED_64_SIZE;
        writer.writeByteArray(fieldData, 0, fieldData.length);
        writer.writeUInt32(len);
        for (double v : values) {
            writer.writeFixed64(Double.doubleToRawLongBits(v));
        }
    }


    protected void writeArrayBoolean(ProtoBufWriter writer, byte[] fieldData, boolean[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length;
        writer.writeByteArray(fieldData, 0, fieldData.length);
        writer.writeUInt32(len);
        for (boolean v : values) {
            writer.writeInt32(v?1:0);
        }
    }

    protected void writeArrayBoolean(ProtoBufWriter writer, byte[] fieldData, Boolean[] values) {
        if (isEmpty(values)) {
            return;
        }
        int len = values.length;
        writer.writeByteArray(fieldData, 0, fieldData.length);
        writer.writeUInt32(len);
        for (boolean v : values) {
            writer.writeInt32(v?1:0);
        }
    }


    protected void writeArrayInt(ProtoBufWriter writer, byte[] fieldData, int[] values, Type type) {
        writer.writePackedInts(fieldData, values, type);
    }

    protected void writeArrayInt(ProtoBufWriter writer, byte[] fieldData, Integer[] values, Type type) {
        writer.writePackedInts(fieldData, values, type);
    }

    protected void writeArrayLong(ProtoBufWriter writer, byte[] fieldData, Long[] values, Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        writer.writePackedLongs(fieldData, values, type);
    }

    protected void writeArrayLong(ProtoBufWriter writer, byte[] fieldData, long[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        writer.writePackedLongs(fieldData, values, type);
    }

    protected void writeString(ProtoBufWriter writer, byte[] fieldData, String value) {
        if (null == value || value.length() == 0) {
            return;
        }
        writer.writeString(fieldData, value);
    }

    protected <E extends Enum<E>> void writeArrayEnum(ProtoBufWriter writer, byte[] fieldData,
                                                      E[] vs) {
        if (vs == null || vs.length == 0) {
            return;
        }
        int len = 0;
        for (E v : vs) {
            len += computeRawVarint32Size(v.ordinal());
        }
        writer.writeByteArray(fieldData, 0, fieldData.length);
        writer.writeUInt32(len);
        for (E v : vs) {
            writer.writeUInt32(v.ordinal());
        }
    }

}