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

import java.math.BigDecimal;
import java.math.BigInteger;

import static io.edap.protobuf.ext.AnyCodec.RANGE_BIGDDECIMAL;

/**
 * BigDecimalCodec的编解码器，编码时将BigDecimal编码为BigInteger和scale()两部分
 */
public class BigDecimalCodec implements ExtCodec<BigDecimal> {

    @Override
    public BigDecimal decode(ProtoBufReader reader) throws ProtoBufException {
        BigInteger unscale = new BigInteger(reader.readBytes());
        int scale = reader.readInt32();
        if (BigInteger.ZERO.equals(unscale) && scale == 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(unscale, scale);
    }

    @Override
    public void encode(ProtoBufWriter writer, BigDecimal bigDecimal) throws EncodeException {
        if (writer.getWriteOrder() == ProtoBufWriter.WriteOrder.SEQUENTIAL) {
            if (bigDecimal == BigDecimal.ZERO) {
                writer.writeInt32(RANGE_BIGDDECIMAL);
                writer.writeBytes(new byte[]{1,0,0});
            } else {
                writer.writeInt32(RANGE_BIGDDECIMAL);
                byte[] bs = bigDecimal.unscaledValue().toByteArray();
                writer.writeByteArray(bs, 0, bs.length);
                writer.writeInt32(bigDecimal.scale(), true);
            }
        } else {
            if (bigDecimal == BigDecimal.ZERO) {
                writer.writeBytes(new byte[]{1,0,0});
                writer.writeInt32(RANGE_BIGDDECIMAL);
            } else {
                byte[] bs = bigDecimal.unscaledValue().toByteArray();
                writer.writeInt32(bigDecimal.scale(), true);
                writer.writeByteArray(bs, 0, bs.length);
                writer.writeInt32(RANGE_BIGDDECIMAL);
            }
        }

    }
}
