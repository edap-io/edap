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

import java.util.HashMap;

import static io.edap.protobuf.ext.AnyCodec.RANGE_MESSAGE;

/**
 * 普通javabean的编解码器
 */
public class MessageCodec implements ExtCodec<Object> {

    HashMap<String, ProtoBufDecoder> DECODERS = new HashMap<>();

    @Override
    public Object decode(ProtoBufReader reader) throws ProtoBufException {
        String uri = reader.readString();
        ProtoBufDecoder decoder = getDecoder(uri);
        if (null == decoder) {
            throw new ProtoBufException(uri + "'s Decoder not found!");
        }
        return reader.readMessage(decoder);
    }

    private ProtoBufDecoder getDecoder(String uri) {
        ProtoBufDecoder decoder = DECODERS.get(uri);
        if (null == decoder) {
            try {
                Class cls = Class.forName(uri);
                decoder = ProtoBufCodecRegister.INSTANCE.getDecoder(cls);
                DECODERS.put(uri, decoder);
            } catch (Exception e) {

            }
        }
        return decoder;
    }

    @Override
    public void encode(ProtoBufWriter writer, Object t) throws EncodeException {
        ProtoBufEncoder<Object> encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(t.getClass());
        writer.writeByte((byte)RANGE_MESSAGE);
        writer.writeString(t.getClass().getName());
        writer.writeMessage(t, encoder);
    }
}
