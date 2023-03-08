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

import io.edap.json.JsonDecoder;
import io.edap.json.JsonParseException;
import io.edap.json.JsonReader;
import io.edap.json.model.DataRange;
import io.edap.json.test.model.DemoOneString;

import java.lang.reflect.InvocationTargetException;

public class DemoOneStringDecoder implements JsonDecoder<DemoOneString> {
    @Override
    public DemoOneString decode(JsonReader jsonReader) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        char c = jsonReader.firstNotSpaceChar();
        if (c != '{') {
            return null;
        }
        jsonReader.nextPos(1);
        c = jsonReader.firstNotSpaceChar();
        DemoOneString pojo = new DemoOneString();
        if (c == '}') {
            return pojo;
        }
        DataRange<byte[]> dr = jsonReader.readKeyRange();
        switch (dr.hashCode()) {
            case 1212206434:
                pojo.setField1(jsonReader.readString());
                break;
            default:
                jsonReader.skipValue();
                break;
        }
        c = jsonReader.firstNotSpaceChar();
        while (c == ',') {
            jsonReader.nextPos(1);
            dr = jsonReader.readKeyRange();
            switch (dr.hashCode()) {
                case 1212206434:
                    pojo.setField1(jsonReader.readString());
                    break;
                default:
                    jsonReader.skipValue();
                    break;
            }
            c = jsonReader.firstNotSpaceChar();
        }
        if (c != '}') {
            throw new JsonParseException("key and value 后为不符合json字符[" + (char)c + "]");
        }
        return pojo;
    }
}
