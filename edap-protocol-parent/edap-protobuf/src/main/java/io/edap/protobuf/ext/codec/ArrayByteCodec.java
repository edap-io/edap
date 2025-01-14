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
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ext.ExtCodec;

import static io.edap.protobuf.ext.AnyCodec.RANGE_ARRAY_BYTE;

/**
 * byte数组编解码器
 */
public class ArrayByteCodec implements ExtCodec<byte[]> {

    @Override
    public byte[] decode(ProtoBufReader reader) throws ProtoException {
        return reader.readBytes();
    }

    @Override
    public boolean skip(ProtoBufReader reader) throws ProtoException {
        int len = reader.readInt32();
        reader.skip(len);
        return true;
    }

    @Override
    public void encode(ProtoBufWriter writer, byte[] bytes) throws EncodeException {
        if (bytes.length == 0) {
            writer.writeByte((byte)RANGE_ARRAY_BYTE);
            writer.writeInt32(0, true);
            return;
        }
        writer.writeByte((byte)RANGE_ARRAY_BYTE);
        writer.writeByteArray(bytes, 0, bytes.length);
    }
}
