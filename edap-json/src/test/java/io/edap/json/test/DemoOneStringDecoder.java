/*
 * Copyright 2023 The edap Project
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

package io.edap.json.test;

import io.edap.json.Decoder;
import io.edap.json.JsonParseException;
import io.edap.json.JsonReader;
import io.edap.json.NodeType;
import io.edap.json.test.model.DemoOneString;

import java.lang.reflect.InvocationTargetException;

public class DemoOneStringDecoder implements Decoder<DemoOneString> {
    @Override
    public DemoOneString decode(JsonReader jsonReader) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        NodeType nodeType = jsonReader.readStart();
        if (nodeType != NodeType.OBJECT) {
            return null;
        }
        jsonReader.nextPos(1);
        char c = jsonReader.firstNotSpaceChar();
        DemoOneString pojo = new DemoOneString();
        if (c == '}') {
            return pojo;
        }
        int hash = jsonReader.keyHash();
        if (hash == 1212206434) {
            pojo.setField1(jsonReader.readString());
        }
        c = jsonReader.firstNotSpaceChar();
        while (c == ',') {
            jsonReader.nextPos(1);

//            hash = jsonReader.keyHash();
//            if (hash == 1212206434) {
//                pojo.field1 = jsonReader.readString();
//            }
//                setter = fieldSetters.get(dr);
//                if (setter != null) {
//                    setter.set(pojo, jsonReader);
//                } else {
//                    jsonReader.skipValue();
//                }
            c = jsonReader.firstNotSpaceChar();
        }
        if (c != '}') {
            throw new JsonParseException("key and value 后为不符合json字符[" + (char)c + "]");
        }

        return pojo;
    }
}
