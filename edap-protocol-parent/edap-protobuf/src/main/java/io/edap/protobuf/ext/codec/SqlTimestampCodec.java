/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.protobuf.ext.codec;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.ext.ExtCodec;

import java.sql.Timestamp;

import static io.edap.protobuf.ext.AnyCodec.RANGE_SQL_TIMEATAMP;

public class SqlTimestampCodec implements ExtCodec<Timestamp> {
    @Override
    public boolean skip(ProtoBufReader reader) throws ProtoException {
        reader.readInt64();
        return true;
    }

    @Override
    public Timestamp decode(ProtoBufReader reader) throws ProtoException {
        return new java.sql.Timestamp(reader.readInt64());
    }

    @Override
    public void encode(ProtoBufWriter writer, Timestamp timestamp) throws EncodeException {
        writer.writeByte((byte)RANGE_SQL_TIMEATAMP);
        writer.writeUInt64(timestamp.getTime());
    }
}
