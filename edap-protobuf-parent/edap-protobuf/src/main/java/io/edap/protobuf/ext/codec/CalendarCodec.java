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

import java.util.Calendar;

import static io.edap.protobuf.ext.AnyCodec.RANGE_CALENDAR;

/**
 * LocalTime的编解码器，将LocalDateTime编码为UTC时区1970-01-01的毫秒时间戳，尽量减小序列化后的体积
 */
public class CalendarCodec implements ExtCodec<Calendar> {

    @Override
    public Calendar decode(ProtoBufReader reader) throws ProtoBufException {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(reader.readInt64());
        return c;
    }

    @Override
    public boolean skip(ProtoBufReader reader) throws ProtoBufException {
        reader.readInt64();
        return true;
    }

    @Override
    public void encode(ProtoBufWriter writer, Calendar v) throws EncodeException {
        writer.writeByte((byte)RANGE_CALENDAR);
        writer.writeUInt64(v.getTimeInMillis());
    }

}
