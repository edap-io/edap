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

import io.edap.protobuf.*;
import io.edap.protobuf.ext.ExtCodec;
import io.edap.protobuf.model.ProtoBufOption;

import java.util.HashMap;

import static io.edap.protobuf.ext.AnyCodec.RANGE_MESSAGE;
import static io.edap.protobuf.wire.WireFormat.makeTag;
import static io.edap.protobuf.wire.WireType.END_GROUP;

/**
 * 普通javabean的编解码器
 */
public class MessageFastCodec implements ExtCodec<Object> {

    HashMap<String, ProtoBufDecoder> DECODERS = new HashMap<>();

    HashMap<Class, ProtoBufEncoder> ENCODERS = new HashMap<>();

    static ProtoBufOption OPTION = new ProtoBufOption();

    static {
        OPTION.setCodecType(ProtoBuf.CodecType.FAST);
    }

    @Override
    public Object decode(ProtoBufReader reader) throws ProtoBufException {
        String uri = reader.readString();
        ProtoBufDecoder decoder = getDecoder(uri);
        if (null == decoder) {
            throw new ProtoBufException(uri + "'s Decoder not found!");
        }
        return reader.readMessage(decoder, makeTag(1, END_GROUP));
    }

    private ProtoBufDecoder getDecoder(String uri) {
        ProtoBufDecoder decoder = DECODERS.get(uri);
        if (null == decoder) {
            try {
                Class cls = Class.forName(uri);
                ProtoBufOption option = new ProtoBufOption();
                option.setCodecType(ProtoBuf.CodecType.FAST);
                decoder = ProtoBufCodecRegister.INSTANCE.getDecoder(cls, option);
                DECODERS.put(uri, decoder);
            } catch (Exception e) {

            }
        }
        return decoder;
    }

    @Override
    public void encode(ProtoBufWriter writer, Object t) throws EncodeException {
        ProtoBufEncoder<Object> encoder = ENCODERS.get(t.getClass());
        if (encoder == null) {
            encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(t.getClass(), OPTION);
            if (encoder != null) {
                ENCODERS.put(t.getClass(), encoder);
            }
        }
        writer.writeByte((byte)RANGE_MESSAGE);
        writer.writeString(t.getClass().getName());
        writer.writeMessage(t, encoder);
    }
}
