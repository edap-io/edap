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

package io.edap.protobuf.ext.codec;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.ProtoReader;
import io.edap.protobuf.ProtoWriter;
import io.edap.protobuf.ext.ExtCodec;

import static io.edap.protobuf.ProtoWriter.encodeZigZag64;
import static io.edap.protobuf.ext.AnyCodec.RANGE_ARRAY_LONG_OBJ;

public class ArrayLongObjCodec implements ExtCodec<Long[]> {

    byte[] NULL_BS = new byte[]{(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,3};

    @Override
    public Long[] decode(ProtoReader reader) throws ProtoException {
        int len = reader.readInt32();
        Long[] is = new Long[len];
        for (int i=0;i<len;i++) {
            long v = reader.readSInt64();
            if (v == Long.MIN_VALUE && reader.getByte(reader.getPos()-1) == 3) {
                is[i] = null;
            } else {
                is[i] = new Long(v);
            }
        }
        return is;
    }

    @Override
    public boolean skip(ProtoReader reader) throws ProtoException {
        int len = reader.readInt32();
        for (int i=0;i<len;i++) {
            reader.readInt64();
        }
        return true;
    }

    @Override
    public void encode(ProtoWriter writer, Long[] longs) throws EncodeException {

        int len = longs.length;
        if (len == 0) {
            writer.writeByte((byte)RANGE_ARRAY_LONG_OBJ);
            writer.writeInt32(0, true);
            return;
        }
        writer.writeByte((byte)RANGE_ARRAY_LONG_OBJ);
        writer.writeInt32(len);
        for (int i=0;i<len;i++) {
            if (null == longs[i]) {
                writer.writeBytes(NULL_BS);
            } else {
                writer.writeUInt64(encodeZigZag64(longs[i]));
            }
        }
    }
}
