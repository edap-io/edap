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
import io.edap.json.JsonReader;
import io.edap.json.NodeType;
import io.edap.json.ValueSetter;
import io.edap.json.model.DataRange;
import io.edap.json.model.StringDataRange;
import io.edap.json.test.model.DemoPojo;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

public class DemoDecoder implements Decoder<DemoPojo> {

    static final Map<DataRange, ValueSetter<DemoPojo>> SETTER_MAP = new HashMap<>();

    static {
        SETTER_MAP.put(StringDataRange.from("name"), (p, v) -> p.setName(v.readString()));
        SETTER_MAP.put(StringDataRange.from("age"), (p, v) -> p.setAge(v.readInt()));
    }

    public DemoDecoder() {

    }
    @Override
    public DemoPojo decode(JsonReader jsonReader) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        NodeType nodeType = jsonReader.readStart();
        if (nodeType != NodeType.OBJECT) {
            return null;
        }
        jsonReader.nextPos(1);
        char c = jsonReader.firstNotSpaceChar();
        DemoPojo pojo = new DemoPojo();
        if (c == '}') {
            return pojo;
        }
        Map<DataRange, ValueSetter<DemoPojo>> _setterMap = SETTER_MAP;
        DataRange dr = jsonReader.readKeyRange();
        ValueSetter<DemoPojo> setter = _setterMap.get(dr);
        if (setter != null) {
            setter.set(pojo, jsonReader);
        } else {
            jsonReader.skipValue();
        }
        dr = jsonReader.readKeyRange();
        setter = _setterMap.get(dr);
        if (setter != null) {
            setter.set(pojo, jsonReader);
        } else {
            jsonReader.skipValue();
        }
        return pojo;
    }
}
