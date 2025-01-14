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

package io.edap.json.decoders;

import io.edap.json.*;
import io.edap.json.enums.DataType;
import io.edap.json.model.ByteArrayDataRange;
import io.edap.json.model.DataRange;
import io.edap.json.model.JsonFieldInfo;
import io.edap.json.model.StringDataRange;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.edap.json.util.JsonUtil.getCodecFieldInfos;
import static io.edap.json.util.JsonUtil.getJsonFieldName;

/**
 * 反射方式实现的Json解码器
 */
public class ReflectDecoder implements JsonDecoder<Object> {

    private final Class valueType;
    private final Constructor constructor;

    private Map<DataRange, ValueSetter> fieldSetters = new HashMap<>();

    public ReflectDecoder(Class valueType, DataType dataType) {
        this.valueType = valueType;
        try {
            constructor = valueType.getConstructor(new Class[0]);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        List<JsonFieldInfo> fieldInfos = getCodecFieldInfos(valueType);
        for (JsonFieldInfo jfi : fieldInfos) {
            String jsonFieldName = getJsonFieldName(jfi.field, null);
            DataRange dr;
            if (dataType == DataType.STRING) {
                dr = StringDataRange.from(jsonFieldName);
            } else {
                dr = ByteArrayDataRange.from(jsonFieldName);
            }
            if (jfi.setMethod != null) {
                putMethodValueSetter(dr, jfi);
            } else {
                jfi.field.setAccessible(true);
                putFieldValueSetter(dr, jfi);
            }
        }
    }

    private void putFieldValueSetter(DataRange dr, JsonFieldInfo jfi) {
        switch (jfi.field.getType().getName()) {
            case "java.lang.String":
                fieldSetters.put(dr, (bean, reader) -> {
                    try {
                        jfi.field.set(bean, reader.readString());
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });
                break;
            case "int":
            case "java.lang.Integer":
                fieldSetters.put(dr, (bean, reader) -> {
                    try {
                        jfi.field.set(bean, reader.readInt());
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });
                break;
            case "long":
            case "java.lang.Long":
                fieldSetters.put(dr, (bean, reader) -> {
                    try {
                        jfi.field.set(bean, reader.readLong());
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });
                break;
            case "float":
            case "java.lang.Float":
                fieldSetters.put(dr, (bean, reader) -> {
                    try {
                        jfi.field.set(bean, reader.readFloat());
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });
                break;
            case "double":
            case "java.lang.Double":
                fieldSetters.put(dr, (bean, reader) -> {
                    try {
                        jfi.field.set(bean, reader.readDouble());
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });
                break;
            case "boolean":
            case "java.lang.Boolean":
                fieldSetters.put(dr, (bean, reader) -> {
                    try {
                        jfi.field.set(bean, reader.readBoolean());
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });
                break;
        }
    }

    private void putMethodValueSetter(DataRange dr, JsonFieldInfo jfi) {
        switch (jfi.field.getType().getName()) {
            case "java.lang.String":
                fieldSetters.put(dr, (bean, reader) -> {
                    try {
                        jfi.method.invoke(bean, reader.readString());
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    } catch (InvocationTargetException e) {
                        throw new RuntimeException(e);
                    }
                });
                break;
        }
    }

    @Override
    public Object decode(JsonReader jsonReader) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        NodeType nodeType = jsonReader.readStart();
        if (nodeType != NodeType.OBJECT) {
            return null;
        }
        jsonReader.nextPos(1);
        char c = jsonReader.firstNotSpaceChar();
        Object pojo = constructor.newInstance(new Object[0]);
        if (c == '}') {
            return pojo;
        }
        DataRange dr = jsonReader.readKeyRange();
        ValueSetter setter = fieldSetters.get(dr);
        if (setter != null) {
            setter.set(pojo, jsonReader);
        } else {
            jsonReader.skipValue();
        }
        c = jsonReader.firstNotSpaceChar();
        while (true) {
            if (c == '}') {
                break;
            } else if (c == ',') {
                jsonReader.nextPos(1);
                dr = jsonReader.readKeyRange();
                setter = fieldSetters.get(dr);
                if (setter != null) {
                    setter.set(pojo, jsonReader);
                } else {
                    jsonReader.skipValue();
                }
                c = jsonReader.firstNotSpaceChar();
            } else {
                throw new JsonParseException("key and value 后为不符合json字符[" + (char)c + "]");
            }
        }
        return pojo;
    }

}
