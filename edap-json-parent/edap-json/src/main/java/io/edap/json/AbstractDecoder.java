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

package io.edap.json;

import io.edap.json.enums.DataType;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public class AbstractDecoder {

    public <T> List<T> readList(JsonReader reader, Class<T> pojo)
            throws InvocationTargetException, InstantiationException, IllegalAccessException {
        JsonDecoder<T> decoder = JsonCodecRegister.instance().getDecoder(pojo, DataType.STRING);
        char c = reader.firstNotSpaceChar();
        if (c != '[') {
            throw new JsonParseException("不是数组类型数据");
        }
        reader.nextPos(1);
        c = reader.firstNotSpaceChar();
        List<T> list = new ArrayList<>();
        while (c != ']') {
            if (c == ',') {
                reader.nextPos(1);
            }
            list.add(decoder.decode(reader));
            c = reader.firstNotSpaceChar();
        }
        reader.nextPos(1);
        return list;
    }
}
