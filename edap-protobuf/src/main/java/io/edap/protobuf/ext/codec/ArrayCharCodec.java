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
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ext.ExtCodec;

import static io.edap.protobuf.ext.AnyCodec.RANGE_ARRAY_CHAR;

/**
 * char数组的编解码器
 */
public class ArrayCharCodec implements ExtCodec<char[]> {

    @Override
    public char[] decode(ProtoBufReader reader) throws ProtoBufException {
        byte[] bs = reader.readBytes();
        char[] cs = new char[bs.length/2];
        int pos = 0;
        for (int i=0;i<bs.length;i++) {
            cs[pos++] = (char)((bs[i++] & 0xFF) | ((bs[i] & 0xFF)) << 8);
        }
        return cs;
    }

    @Override
    public void encode(ProtoBufWriter writer, char[] chars) throws EncodeException {
        int len = chars.length;
        byte[] bs = new byte[len<<1];
        int pos = 0;
        for (int i=0;i<len;i++) {
            int v = chars[i];
            bs[pos++] = (byte)v;
            bs[pos++] = (byte)(v >> 8);
        }
        writer.writeByte((byte)RANGE_ARRAY_CHAR);
        writer.writeByteArray(bs, 0, bs.length);
    }
}
