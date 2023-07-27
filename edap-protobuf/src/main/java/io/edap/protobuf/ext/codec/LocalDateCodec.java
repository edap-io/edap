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

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static io.edap.protobuf.ext.AnyCodec.RANGE_LOCALDATE;

/**
 * LocalDate的编解码器，将LocalDate计算与2000-01-01这个日期间隔的天数，反序列化时，将2000-01-01加上序列化的天数转换为LocalDate
 */
public class LocalDateCodec implements ExtCodec<LocalDate> {

    private static final LocalDate START_DAY = LocalDate.of(2000, 1, 1);

    @Override
    public LocalDate decode(ProtoBufReader reader) throws ProtoBufException {
        return START_DAY.plusDays(reader.readInt32());
    }

    @Override
    public boolean skip(ProtoBufReader reader) throws ProtoBufException {
        reader.readInt32();
        return true;
    }

    @Override
    public void encode(ProtoBufWriter writer, LocalDate v) throws EncodeException {
        writer.writeByte((byte)RANGE_LOCALDATE);
        writer.writeInt32((int)START_DAY.until(v, ChronoUnit.DAYS), true);
    }
}
