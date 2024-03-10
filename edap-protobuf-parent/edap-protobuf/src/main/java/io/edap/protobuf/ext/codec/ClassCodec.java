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
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.ProtoReader;
import io.edap.protobuf.ProtoWriter;
import io.edap.protobuf.ext.ExtCodec;

import static io.edap.protobuf.ext.AnyCodec.RANGE_CLASS;

/**
 * Class对象的编解码器
 */
public class ClassCodec implements ExtCodec<Class> {

    @Override
    public Class decode(ProtoReader reader) throws ProtoException {
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
                    throw new ProtoException("Class " + s + " not found");
                }
        }
    }

    @Override
    public boolean skip(ProtoReader reader) throws ProtoException {
        reader.readString();
        return true;
    }

    @Override
    public void encode(ProtoWriter writer, Class aClass) throws EncodeException {
        String s = null;
        if (aClass != null) {
            s = aClass.getName();
        }
        writer.writeByte((byte)RANGE_CLASS);
        writer.writeString(s);
    }
}
