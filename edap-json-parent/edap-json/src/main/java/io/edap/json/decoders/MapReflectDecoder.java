/*
 * Copyright 2026 The edap Project
 *
 * The edap Project licenses this file to you under the Apache License,
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

import io.edap.json.AbstractMapBeanDecoder;
import io.edap.json.JsonCodecRegister;
import io.edap.json.MapBeanDecoder;
import io.edap.json.model.JsonFieldInfo;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static io.edap.json.util.JsonUtil.getCodecFieldInfos;
import static io.edap.json.util.JsonUtil.getJsonFieldName;
import static io.edap.util.AsmUtil.isList;
import static io.edap.util.AsmUtil.isPojo;

/**
 * 反射方式实现的 {@code Map<String, Object> → JavaBean} 解码器。
 *
 * <p>与 {@code ReflectDecoder} 对称 —— 当 {@code MapBeanDecoderGenerator} ASM 生成失败时
 *     作为 fallback。能力对齐：primitives + wrappers + String + BigDecimal + Date +
 *     嵌套 POJO + {@code List<T>} + {@code T[]} + 数字类型容错（通过
 *     {@code JsonUtil.getXxxValue}）。</p>
 */
public class MapReflectDecoder extends AbstractMapBeanDecoder {

    private final Class<?> valueType;
    private final Constructor<?> constructor;

    public MapReflectDecoder(Class<?> valueType) {
        this.valueType = valueType;
        try {
            this.constructor = valueType.getConstructor();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("no no-arg constructor on " + valueType.getName(), e);
        }
    }

    @Override
    public Object decode(Map<String, Object> map) {
        Object bean;
        try {
            bean = constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("new instance of " + valueType.getName() + " failed", e);
        }
        for (JsonFieldInfo jfi : getCodecFieldInfos(valueType)) {
            String key = getJsonFieldName(jfi.field, jfi.jsonFieldName);
            Object v = map.get(key);
            if (v == null) {
                continue;
            }
            assign(bean, jfi, v);
        }
        return bean;
    }

    private void assign(Object bean, JsonFieldInfo jfi, Object v) {
        Class<?> rawType = jfi.field.getType();
        try {
            if (rawType == String.class) {
                writeFieldOrInvoke(bean, jfi, (String) v);
                return;
            }
            if (rawType == int.class) {
                writeFieldOrInvoke(bean, jfi, io.edap.json.util.JsonUtil.getIntValue(v));
                return;
            }
            if (rawType == Integer.class) {
                writeFieldOrInvoke(bean, jfi, io.edap.json.util.JsonUtil.getIntValue(v));
                return;
            }
            if (rawType == long.class) {
                writeFieldOrInvoke(bean, jfi, io.edap.json.util.JsonUtil.getLongValue(v));
                return;
            }
            if (rawType == Long.class) {
                writeFieldOrInvoke(bean, jfi, io.edap.json.util.JsonUtil.getLongValue(v));
                return;
            }
            if (rawType == boolean.class) {
                writeFieldOrInvoke(bean, jfi, io.edap.json.util.JsonUtil.getBooleanValue(v));
                return;
            }
            if (rawType == Boolean.class) {
                writeFieldOrInvoke(bean, jfi, io.edap.json.util.JsonUtil.getBooleanValue(v));
                return;
            }
            if (rawType == float.class) {
                writeFieldOrInvoke(bean, jfi, io.edap.json.util.JsonUtil.getFloatValue(v));
                return;
            }
            if (rawType == Float.class) {
                writeFieldOrInvoke(bean, jfi, io.edap.json.util.JsonUtil.getFloatValue(v));
                return;
            }
            if (rawType == double.class) {
                writeFieldOrInvoke(bean, jfi, io.edap.json.util.JsonUtil.getDoubleValue(v));
                return;
            }
            if (rawType == Double.class) {
                writeFieldOrInvoke(bean, jfi, io.edap.json.util.JsonUtil.getDoubleValue(v));
                return;
            }
            if (rawType == BigDecimal.class) {
                writeFieldOrInvoke(bean, jfi, (BigDecimal) v);
                return;
            }
            if (rawType == Date.class) {
                writeFieldOrInvoke(bean, jfi, (Date) v);
                return;
            }
            if (rawType == Object.class) {
                writeFieldOrInvoke(bean, jfi, v);
                return;
            }
            if (isPojo(rawType)) {
                if (v instanceof Map) {
                    MapBeanDecoder<?> sub = JsonCodecRegister.instance().getMapBeanDecoder(rawType);
                    writeFieldOrInvoke(bean, jfi, sub.decode((Map<String, Object>) v));
                }
                return;
            }
            if (rawType.isArray() && !rawType.getComponentType().isPrimitive()) {
                Class<?> compType = rawType.getComponentType();
                if (v instanceof List) {
                    List<?> src = (List<?>) v;
                    Object arr = Array.newInstance(compType, src.size());
                    MapBeanDecoder<?> sub = isPojo(compType)
                            ? JsonCodecRegister.instance().getMapBeanDecoder(compType)
                            : null;
                    for (int i = 0; i < src.size(); i++) {
                        Object item = src.get(i);
                        Object cast;
                        if (item == null) {
                            cast = null;
                        } else if (sub != null && item instanceof Map) {
                            cast = sub.decode((Map<String, Object>) item);
                        } else {
                            cast = item;
                        }
                        Array.set(arr, i, cast);
                    }
                    writeFieldOrInvokeRaw(bean, jfi, arr);
                }
                return;
            }
            if (isList(rawType) && jfi.field.getGenericType() instanceof ParameterizedType) {
                ParameterizedType ptype = (ParameterizedType) jfi.field.getGenericType();
                Class<?> itemType = (Class<?>) ptype.getActualTypeArguments()[0];
                if (v instanceof List) {
                    List<?> src = (List<?>) v;
                    List<Object> out = new ArrayList<>(src.size());
                    MapBeanDecoder<?> sub = isPojo(itemType)
                            ? JsonCodecRegister.instance().getMapBeanDecoder(itemType)
                            : null;
                    for (Object item : src) {
                        if (item == null) {
                            out.add(null);
                        } else if (sub != null && item instanceof Map) {
                            out.add(sub.decode((Map<String, Object>) item));
                        } else {
                            out.add(item);
                        }
                    }
                    writeFieldOrInvoke(bean, jfi, out);
                }
                return;
            }
            // 兜底：强转
            writeFieldOrInvoke(bean, jfi, v);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("assign field " + jfi.field.getName()
                    + " on " + valueType.getName() + " failed", e);
        }
    }

    private void writeFieldOrInvoke(Object bean, JsonFieldInfo jfi, Object value)
            throws IllegalAccessException, InvocationTargetException {
        if (jfi.setMethod != null) {
            Object rtn = jfi.setMethod.invoke(bean, value);
        } else {
            jfi.field.setAccessible(true);
            jfi.field.set(bean, value);
        }
    }

    /** primitive 类型走 setter → 需要 primitive 入参（不可传包装）。 */
    private void writeFieldOrInvokeRaw(Object bean, JsonFieldInfo jfi, Object value)
            throws IllegalAccessException, InvocationTargetException {
        if (jfi.setMethod != null) {
            Method m = jfi.setMethod;
            Class<?> pt = m.getParameterTypes()[0];
            Object coerced = coerceForSetter(value, pt);
            m.invoke(bean, coerced);
        } else {
            jfi.field.setAccessible(true);
            jfi.field.set(bean, value);
        }
    }

    private void writeFieldOrInvoke(Object bean, JsonFieldInfo jfi, int value)
            throws IllegalAccessException, InvocationTargetException {
        if (jfi.setMethod != null) {
            Class<?> pt = jfi.setMethod.getParameterTypes()[0];
            if (pt == Integer.class) {
                jfi.setMethod.invoke(bean, value);
            } else {
                jfi.setMethod.invoke(bean, value);
            }
        } else {
            jfi.field.setAccessible(true);
            jfi.field.setInt(bean, value);
        }
    }

    private void writeFieldOrInvoke(Object bean, JsonFieldInfo jfi, long value)
            throws IllegalAccessException, InvocationTargetException {
        if (jfi.setMethod != null) {
            jfi.setMethod.invoke(bean, value);
        } else {
            jfi.field.setAccessible(true);
            jfi.field.setLong(bean, value);
        }
    }

    private void writeFieldOrInvoke(Object bean, JsonFieldInfo jfi, boolean value)
            throws IllegalAccessException, InvocationTargetException {
        if (jfi.setMethod != null) {
            jfi.setMethod.invoke(bean, value);
        } else {
            jfi.field.setAccessible(true);
            jfi.field.setBoolean(bean, value);
        }
    }

    private void writeFieldOrInvoke(Object bean, JsonFieldInfo jfi, float value)
            throws IllegalAccessException, InvocationTargetException {
        if (jfi.setMethod != null) {
            jfi.setMethod.invoke(bean, value);
        } else {
            jfi.field.setAccessible(true);
            jfi.field.setFloat(bean, value);
        }
    }

    private void writeFieldOrInvoke(Object bean, JsonFieldInfo jfi, double value)
            throws IllegalAccessException, InvocationTargetException {
        if (jfi.setMethod != null) {
            jfi.setMethod.invoke(bean, value);
        } else {
            jfi.field.setAccessible(true);
            jfi.field.setDouble(bean, value);
        }
    }

    private Object coerceForSetter(Object value, Class<?> paramType) {
        if (value == null || paramType.isInstance(value)) {
            return value;
        }
        if (paramType == int.class || paramType == Integer.class) {
            return io.edap.json.util.JsonUtil.getIntValue(value);
        }
        if (paramType == long.class || paramType == Long.class) {
            return io.edap.json.util.JsonUtil.getLongValue(value);
        }
        if (paramType == boolean.class || paramType == Boolean.class) {
            return io.edap.json.util.JsonUtil.getBooleanValue(value);
        }
        if (paramType == float.class || paramType == Float.class) {
            return io.edap.json.util.JsonUtil.getFloatValue(value);
        }
        if (paramType == double.class || paramType == Double.class) {
            return io.edap.json.util.JsonUtil.getDoubleValue(value);
        }
        return value;
    }
}