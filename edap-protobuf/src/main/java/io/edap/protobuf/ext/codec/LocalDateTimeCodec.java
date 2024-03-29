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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static io.edap.protobuf.ext.AnyCodec.RANGE_LOCALDATETIME;

/**
 * LocalDateTime的编解码器，将LocalDateTime编码为UTC时区的毫秒时间戳
 */
public class LocalDateTimeCodec implements ExtCodec<LocalDateTime> {

    @Override
    public LocalDateTime decode(ProtoBufReader reader) throws ProtoBufException {
        return Instant.ofEpochMilli(reader.readInt64()).atZone(ZoneOffset.UTC).toLocalDateTime();
    }

    @Override
    public void encode(ProtoBufWriter writer, LocalDateTime v) throws EncodeException {
        if (writer.getWriteOrder() == ProtoBufWriter.WriteOrder.SEQUENTIAL) {
            writer.writeByte((byte)RANGE_LOCALDATETIME);
            writer.writeUInt64(v.toInstant(ZoneOffset.UTC).toEpochMilli());
        } else {
            writer.writeUInt64(v.toInstant(ZoneOffset.UTC).toEpochMilli());
            writer.writeByte((byte)RANGE_LOCALDATETIME);
        }
    }
}
