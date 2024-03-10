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

import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.wire.Field;
import io.edap.protobuf.wire.Field.Type;
import io.edap.util.CollectionUtils;

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

    private ProtoBufOption option;

    public void setProtoBufOption(ProtoBufOption option) {
        this.option = option;
    }

    public ProtoBufOption getProtoBufOption() {
        return option;
    }

    protected void writeArrayFloat(ProtoWriter writer, byte[] fieldData, float[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length * FIXED_32_SIZE;
        writer.writeBytes(fieldData);
        writer.writeUInt32(len);
        for (float v : values) {
            writer.writeFixed32(Float.floatToRawIntBits(v));
        }
    }

    protected void writeArrayFloat(ProtoWriter writer, byte[] fieldData, Float[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length * FIXED_32_SIZE;
        writer.writeBytes(fieldData);
        writer.writeUInt32(len);
        for (float v : values) {
            writer.writeFixed32(Float.floatToRawIntBits(v));
        }
    }

    protected void writeArrayDouble(ProtoWriter writer, byte[] fieldData, double[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length * FIXED_64_SIZE;
        writer.writeBytes(fieldData);
        writer.writeUInt32(len);
        for (double v : values) {
            writer.writeFixed64(Double.doubleToRawLongBits(v));
        }
    }

    protected void writeArrayDouble(ProtoWriter writer, byte[] fieldData, Double[] values) {
        if (isEmpty(values)) {
            return;
        }
        int len = values.length * FIXED_64_SIZE;
        writer.writeBytes(fieldData);
        writer.writeUInt32(len);
        for (double v : values) {
            writer.writeFixed64(Double.doubleToRawLongBits(v));
        }
    }


    protected void writeArrayBoolean(ProtoWriter writer, byte[] fieldData, boolean[] values) {
        writer.writePackedBooleans(fieldData, values);
    }

    protected void writeArrayBoolean(ProtoWriter writer, byte[] fieldData, Boolean[] values) {
        if (CollectionUtils.isEmpty(values)) {
            return;
        }
        writer.writePackedBooleans(fieldData, values);
    }


    protected void writeArrayInt(ProtoWriter writer, byte[] fieldData, int[] values, Type type) {
        writer.writePackedInts(fieldData, values, type);
    }

    protected void writeArrayInt(ProtoWriter writer, byte[] fieldData, short[] values, Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length;
        int[] vals = new int[len];
        for (int i=0;i<len;i++) {
            vals[i] = values[i];
        }
        writer.writePackedInts(fieldData, vals, type);
    }

    protected void writeArrayInt(ProtoWriter writer, byte[] fieldData, Short[] values, Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length;
        int[] vals = new int[len];
        for (int i=0;i<len;i++) {
            vals[i] = values[i];
        }
        writer.writePackedInts(fieldData, vals, type);
    }

    protected void writeArrayInt(ProtoWriter writer, byte[] fieldData, char[] values, Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length;
        int[] vals = new int[len];
        for (int i=0;i<len;i++) {
            vals[i] = values[i];
        }
        writer.writePackedInts(fieldData, vals, type);
    }

    protected void writeArrayInt(ProtoWriter writer, byte[] fieldData, Character[] values, Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length;
        int[] vals = new int[len];
        for (int i=0;i<len;i++) {
            vals[i] = values[i];
        }
        writer.writePackedInts(fieldData, vals, type);
    }

    protected void writeArrayInt(ProtoWriter writer, byte[] fieldData, Integer[] values, Type type) {
        writer.writePackedInts(fieldData, values, type);
    }

    protected void writeArrayLong(ProtoWriter writer, byte[] fieldData, Long[] values, Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        writer.writePackedLongs(fieldData, values, type);
    }

    protected void writeArrayLong(ProtoWriter writer, byte[] fieldData, long[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        writer.writePackedLongs(fieldData, values, type);
    }

    protected void writeString(ProtoWriter writer, byte[] fieldData, String value) {
        if (null == value || value.length() == 0) {
            return;
        }
        writer.writeString(fieldData, value);
    }

    protected <E extends Enum<E>> void writeArrayEnum(ProtoWriter writer, byte[] fieldData,
                                                      E[] vs) {
        if (vs == null || vs.length == 0) {
            return;
        }
        int len = 0;
        for (E v : vs) {
            len += computeRawVarint32Size(v.ordinal());
        }
        writer.writeBytes(fieldData);
        writer.writeUInt32(len);
        for (E v : vs) {
            writer.writeUInt32(v.ordinal());
        }
    }

}