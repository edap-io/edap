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


import io.edap.io.ByteArrayBufOut;
import io.edap.json.writer.ByteArrayJsonWriter;
import io.edap.util.CollectionUtils;

import java.io.UnsupportedEncodingException;
import java.util.Collection;

public class Eson {

    private static final ThreadLocal<StringJsonReader> THREAD_STRING_JSONREADER;

    private static final ThreadLocal<ByteArrayJsonReader> THREAD_BYTEARRAY_JSONREADER;

    private static final ThreadLocal<StringJson5Reader> THREAD_STRING_JSON5READER;

    private static final ThreadLocal<ByteArrayJson5Reader> THREAD_BYTEARRAY_JSON5READER;

    static  {
        THREAD_STRING_JSONREADER  = ThreadLocal.withInitial(() -> new StringJsonReader(""));

        THREAD_BYTEARRAY_JSONREADER  = ThreadLocal.withInitial(() -> new ByteArrayJsonReader(new byte[0]));

        THREAD_STRING_JSON5READER  = ThreadLocal.withInitial(() -> new StringJson5Reader(""));

        THREAD_BYTEARRAY_JSON5READER  = ThreadLocal.withInitial(() -> new ByteArrayJson5Reader(new byte[0]));
    }

    /**
     * 本地线程的ProtoBuf的Writer减少内存分配次数
     */
    public static final ThreadLocal<JsonWriter> THREAD_WRITER;

    static {
        THREAD_WRITER = ThreadLocal.withInitial(() -> {
            ByteArrayBufOut out    = new ByteArrayBufOut();
            return new ByteArrayJsonWriter(out);
        });
    }

    public static String toJsonString(Object obj) {
        JsonWriter writer = THREAD_WRITER.get();
        writer.reset();
        serialize(obj, writer);
        try {
            return new String(writer.toByteArray(), "utf-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return "";
    }

    public static void serialize(Object obj, JsonWriter writer) {
        if (obj == null) {
            return;
        }
        if (obj instanceof Collection) {
            Collection list = (Collection)obj;
            if (CollectionUtils.isEmpty(list)) {
                writer.write((byte)'[', (byte)']');
                return;
            }
            JsonEncoder codec;
            int i = 0;
            for (Object c : list) {
                if (i == 0) {
                    writer.write((byte)'[');
                } else {
                    writer.write((byte)',');
                }
                i++;
                codec = JsonCodecRegister.instance().getEncoder(c.getClass());
                codec.encode(writer, c);
            }
            writer.write((byte)']');
        } else if (obj.getClass().isArray()) {
            Class<?> cType = obj.getClass().getComponentType();
            Object[] array = (Object[])obj;
            if (CollectionUtils.isEmpty(array)) {
                writer.write((byte)'[', (byte)']');
                return;
            }
            JsonEncoder codec = JsonCodecRegister.instance().getEncoder(cType);
            for (int i=0;i<array.length;i++) {
                if (i == 0) {
                    writer.write((byte)'[');
                } else {
                    writer.write((byte)',');
                }
                codec.encode(writer, array[i]);
            }
            writer.write((byte)']');
        } else {
            JsonEncoder codec = JsonCodecRegister.instance().getEncoder(obj.getClass());
            codec.encode(writer, obj);
        }
    }

    public static JsonObject parseJsonObject(String json) {
        StringJsonReader reader = THREAD_STRING_JSONREADER.get();
        reader.reset();
        reader.setJsonData(json);
        char c = reader.firstNotSpaceChar();
        if (c != '{') {
            throw new JsonParseException("不是JsonObject的数据");
        }
        reader.nextPos(1);
        return reader.readObjectValue();
    }

    public static <T> T parseObject(String json, Class<T> clazz) {
        StringJsonReader reader = THREAD_STRING_JSONREADER.get();
        reader.reset();
        reader.setJsonData(json);
        char c = reader.firstNotSpaceChar();
        if (c != '{') {
            throw new JsonParseException("不是JsonObject的数据");
        }
        reader.nextPos(1);
        try {
            return reader.readObject(clazz);
        } catch (Throwable t) {
            throw new JsonParseException("parseJson error", t);
        }
    }

    public static JsonObject parseJsonObject(byte[] json) {
        ByteArrayJsonReader reader = THREAD_BYTEARRAY_JSONREADER.get();
        reader.reset();
        reader.setJsonData(json);
        char c = reader.firstNotSpaceChar();
        if (c != '{') {
            throw new JsonParseException("不是JsonObject的数据");
        }
        reader.nextPos(1);
        return reader.readObjectValue();
    }

    public static <T> T parseObject(byte[] json, Class<T> clazz) {
        ByteArrayJsonReader reader = THREAD_BYTEARRAY_JSONREADER.get();
        reader.reset();
        reader.setJsonData(json);
        char c = reader.firstNotSpaceChar();
        if (c != '{') {
            throw new JsonParseException("不是JsonObject的数据");
        }
        reader.nextPos(1);
        try {
            return reader.readObject(clazz);
        } catch (Throwable t) {
            throw new JsonParseException("parseJson error", t);
        }
    }

    public static JsonObject parseV5JsonObject(String json) {
        StringJson5Reader reader = THREAD_STRING_JSON5READER.get();
        reader.reset();
        reader.setJsonData(json);
        char c = reader.firstNotSpaceChar();
        if (c != '{') {
            throw new JsonParseException("不是JsonObject的数据");
        }
        reader.nextPos(1);
        return reader.readObjectValue();
    }

    public static <T> T parseV5Object(String json, Class<T> clazz) {
        StringJson5Reader reader = THREAD_STRING_JSON5READER.get();
        reader.reset();
        reader.setJsonData(json);
        char c = reader.firstNotSpaceChar();
        if (c != '{') {
            throw new JsonParseException("不是JsonObject的数据");
        }
        reader.nextPos(1);
        try {
            return reader.readObject(clazz);
        } catch (Throwable t) {
            throw new JsonParseException("parseJson error", t);
        }
    }

    public static JsonObject parseV5JsonObject(byte[] json) {
        ByteArrayJson5Reader reader = THREAD_BYTEARRAY_JSON5READER.get();
        reader.reset();
        reader.setJsonData(json);
        char c = reader.firstNotSpaceChar();
        if (c != '{') {
            throw new JsonParseException("不是JsonObject的数据");
        }
        reader.nextPos(1);
        return reader.readObjectValue();
    }

    public static <T> T parseV5Object(byte[] json, Class<T> clazz) {
        ByteArrayJson5Reader reader = THREAD_BYTEARRAY_JSON5READER.get();
        reader.reset();
        reader.setJsonData(json);
        char c = reader.firstNotSpaceChar();
        if (c != '{') {
            throw new JsonParseException("不是JsonObject的数据");
        }
        reader.nextPos(1);
        try {
            return reader.readObject(clazz);
        } catch (Throwable t) {
            throw new JsonParseException("parseJson error", t);
        }
    }
}
