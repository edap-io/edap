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

import io.edap.json.AbstractDecoder;
import io.edap.json.JsonDecoder;
import io.edap.json.JsonParseException;
import io.edap.json.JsonReader;
import io.edap.json.model.DataRange;
import io.edap.json.model.StringDataRange;
import io.edap.json.test.model.DemoPojo;

import java.lang.reflect.InvocationTargetException;

public class DemoPojoDecoder2 extends AbstractDecoder implements JsonDecoder<DemoPojo> {

    static DataRange<String> NAME_DR = StringDataRange.from("name");

    static DataRange<String> AGE_DR = StringDataRange.from("age");

    static DataRange<String> OLD_DR = StringDataRange.from("old");

    static DataRange<String> BALANCE_DR = StringDataRange.from("balance");

    static DataRange<String> INTEGRAL_DR = StringDataRange.from("integral");

    @Override
    public DemoPojo decode(JsonReader jsonReader) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        char c = jsonReader.firstNotSpaceChar();
        if (c != '{') {
            return null;
        }
        jsonReader.nextPos(1);
        c = jsonReader.firstNotSpaceChar();
        DemoPojo pojo = new DemoPojo();
        if (c == '}') {
            return pojo;
        }
        DataRange dr = jsonReader.readKeyRange();
        if (NAME_DR.equals(dr)) {
            pojo.setName(jsonReader.readString());
        } else if (AGE_DR.equals(dr)) {
            pojo.setAge(jsonReader.readInt());
        } else if (OLD_DR.equals(dr)) {
            pojo.setOld(jsonReader.readBoolean());
        } else if (BALANCE_DR.equals(dr)) {
            pojo.setBalance(jsonReader.readDouble());
        } else if (INTEGRAL_DR.equals(dr)) {
            pojo.setIntegral(jsonReader.readLong());
        } else {
            jsonReader.skipValue();
        }
        c = jsonReader.firstNotSpaceChar();
        while (c == ',') {
            jsonReader.nextPos(1);
            dr = jsonReader.readKeyRange();
            if (NAME_DR.equals(dr)) {
                pojo.setName(jsonReader.readString());
            } else if (AGE_DR.equals(dr)) {
                pojo.setAge(jsonReader.readInt());
            } else if (OLD_DR.equals(dr)) {
                pojo.setOld(jsonReader.readBoolean());
            } else if (BALANCE_DR.equals(dr)) {
                pojo.setBalance(jsonReader.readDouble());
            } else if (INTEGRAL_DR.equals(dr)) {
                pojo.setIntegral(jsonReader.readLong());
            } else {
                jsonReader.skipValue();
            }
            c = jsonReader.firstNotSpaceChar();
        }
        if (c != '}') {
            throw new JsonParseException("key and value 后为不符合json字符[" + (char)c + "]");
        }
        return pojo;
    }
}
