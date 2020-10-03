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

import static io.edap.protobuf.ext.AnyCodec.RANGE_CLASS;

/**
 * Class对象的编解码器
 */
public class ClassCodec implements ExtCodec<Class> {

    @Override
    public Class decode(ProtoBufReader reader) throws ProtoBufException {
        String s = reader.readString();
        if (s == null) {
            return null;
        }
        switch (s) {
            case "byte":
                return byte.class;
            case "boolean":
                return boolean.class;
            case "short":
                return short.class;
            case "char":
                return char.class;
            case "float":
                return float.class;
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "double":
                return double.class;
            default:
                try {
                    return Class.forName(s);
                } catch (ClassNotFoundException e) {
                    throw new ProtoBufException("Class " + s + " not found");
                }
        }
    }

    @Override
    public void encode(ProtoBufWriter writer, Class aClass) throws EncodeException {
        String s = null;
        if (aClass != null) {
            s = aClass.getName();
        }
        if (writer.getWriteOrder() == ProtoBufWriter.WriteOrder.SEQUENTIAL) {
            writer.writeInt32(RANGE_CLASS);
            writer.writeString(s);
        } else {
            writer.writeString(s);
            writer.writeInt32(RANGE_CLASS);
        }
    }
}
