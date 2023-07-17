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

import java.math.BigInteger;

import static io.edap.protobuf.ext.AnyCodec.RANGE_BIGINTEGER;

/**
 * BigInteger的编解码器实现
 */
public class BigIntegerCodec implements ExtCodec<BigInteger> {

    @Override
    public BigInteger decode(ProtoBufReader reader) throws ProtoBufException {
        byte[] bs = reader.readBytes();
        if (bs.length == 1) {
            switch (bs[0]) {
                case 0:
                    return BigInteger.ZERO;
                case 1:
                    return BigInteger.ONE;
                case 10:
                    return BigInteger.TEN;
            }
        }
        return new BigInteger(bs);
    }

    @Override
    public void encode(ProtoBufWriter writer, BigInteger v) throws EncodeException {
        if (v == BigInteger.ZERO) {
            writer.writeByte((byte)RANGE_BIGINTEGER);
            writer.writeByteArray(new byte[]{0}, 0, 1);
        } else {
            byte[] bs = v.toByteArray();
            writer.writeByte((byte)RANGE_BIGINTEGER);
            writer.writeByteArray(bs, 0, bs.length);
        }
    }
}
