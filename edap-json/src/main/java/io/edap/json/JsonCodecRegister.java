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

import io.edap.json.decoders.ReflectDecoder;
import io.edap.json.encoders.*;
import io.edap.json.enums.DataType;
import io.edap.json.enums.JsonVersion;
import io.edap.log.LoggerManager;
import io.edap.util.internal.GeneratorClassInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.edap.json.util.JsonUtil.buildDecoderName;
import static io.edap.json.util.JsonUtil.buildEncoderName;
import static io.edap.util.AsmUtil.saveJavaFile;
import static io.edap.util.AsmUtil.toLangName;
import static io.edap.util.CollectionUtils.isEmpty;

public class JsonCodecRegister {

    private static final Map<Class<?>, Map<String, JsonDecoder>> DECODER_MAP = new ConcurrentHashMap<>();

    private static final Map<Class<?>, JsonEncoder> ENCODER_MAP = new ConcurrentHashMap<>();

    static {
        ENCODER_MAP.put(Boolean.class, new BooleanEncoder());
        ENCODER_MAP.put(String.class,  new StringEncoder());
        ENCODER_MAP.put(Integer.class, new IntegerEncoder());
        ENCODER_MAP.put(Long.class,    new LongEncoder());
        ENCODER_MAP.put(Object.class,  new ObjectEncoder());
        ENCODER_MAP.put(Double.class,  new DoubleEncoder());
    }

    private final Map<ClassLoader, JsonCodecLoader> codecLoaders = new HashMap<>();

    private JsonCodecRegister() {}

    public <T> JsonEncoder<T> getEncoder(Class<T> tClass) {
        JsonEncoder encoder = ENCODER_MAP.get(tClass);
        if (encoder == null) {
            encoder = generateEncoder(tClass);
            ENCODER_MAP.put(tClass, encoder);
        }
        return encoder;
    }

    public <T> JsonDecoder<T> getDecoder(Class<T> tClass, DataType dataType) {
        return getDecoder(tClass, dataType, JsonVersion.JSON);
    }

    public <T> JsonDecoder<T> getDecoder(Class<T> tClass, DataType dataType, JsonVersion version) {
        String key = dataType + "-" + version;
        Map<String, JsonDecoder> decoders = DECODER_MAP.get(tClass);
        if (decoders == null) {
            decoders = new HashMap<>();
            DECODER_MAP.put(tClass, decoders);
        }
        JsonDecoder decoder = decoders.get(key);
        if (decoder == null) {
            decoder = generateDecoder(tClass, dataType, version);
            decoders.put(key, decoder);
        }

        if (decoder == null) {
            decoder = new ReflectDecoder(tClass, dataType);
            decoders.put(key, decoder);
        }
        return decoder;
    }

    private JsonDecoder generateDecoder(Class cls, DataType dataType, JsonVersion version) {
        JsonDecoder codec = null;
        Class decoderCls = generateDecoderClass(cls, dataType, version);
        if (decoderCls != null) {
            try {
                codec = (JsonDecoder)decoderCls.newInstance();
            } catch (InstantiationException | IllegalAccessException ex) {
                throw new RuntimeException("generateDecoder "
                        + cls.getName() + " error", ex);
            }
        }
        return codec;
    }

    private JsonEncoder generateEncoder(Class cls) {
        JsonEncoder codec = null;
        Class encoderCls = generateEncoderClass(cls);
        if (encoderCls != null) {
            try {
                codec = (JsonEncoder)encoderCls.newInstance();
            } catch (InstantiationException | IllegalAccessException ex) {
                throw new RuntimeException("generateDecoder "
                        + cls.getName() + " error", ex);
            }
        }
        return codec;
    }

    private JsonCodecLoader getEncoderLoader(Class clazz) {
        ClassLoader classLoader = clazz.getClassLoader();
        JsonCodecLoader loader = codecLoaders.get(classLoader);
        if (loader == null) {
            loader = new JsonCodecLoader(classLoader);
        }

        return loader;
    }

    private Class generateEncoderClass(Class cls) {
        Class encoderCls = null;
        long start = System.currentTimeMillis();
        String encoderName = buildEncoderName(cls);
        try {
            encoderCls = Class.forName(encoderName);
            return encoderCls;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        JsonCodecLoader codecLoader = getEncoderLoader(cls);
        try {
            JsonEncoderGenerator generator = new JsonEncoderGenerator(cls);
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            System.out.println("generate class time: " + (System.currentTimeMillis() - start));
            saveJavaFile("./" + gci.clazzName + ".class", bs);
            encoderCls = getEncoderLoader(cls).define(encoderName, bs, 0, bs.length);
            if (!isEmpty(gci.inners)) {
                for (GeneratorClassInfo inner : gci.inners) {
                    bs = inner.clazzBytes;
                    String innerName = toLangName(inner.clazzName);
                    saveJavaFile("./" + inner.clazzName + ".class", bs);
                    codecLoader.define(innerName, bs, 0, bs.length);
                }
            }
        } catch (Throwable e) {
            try {
                if (codecLoader.loadClass(encoderName) != null) {
                    return codecLoader.loadClass(encoderName);
                }
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException("generateDecoder "
                        + cls.getName() + " error", ex);
            }
            throw new RuntimeException("generateDecoder "
                    + cls.getName() + " error", e);
        }

        return encoderCls;
    }

    private Class generateDecoderClass(Class cls, DataType dataType, JsonVersion version) {
        Class decoderCls = null;
        long start = System.currentTimeMillis();
        String decoderName = buildDecoderName(cls, dataType, version);
        try {
            decoderCls = Class.forName(decoderName);
            return decoderCls;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        JsonCodecLoader codecLoader = getEncoderLoader(cls);
        try {
            JsonDecoderGenerator generator = new JsonDecoderGenerator(cls, dataType, version);
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            saveJavaFile("./" + gci.clazzName + ".class", bs);
            decoderCls = codecLoader.define(decoderName, bs, 0, bs.length);
            if (!isEmpty(gci.inners)) {
                for (GeneratorClassInfo inner : gci.inners) {
                    bs = inner.clazzBytes;
                    String innerName = toLangName(inner.clazzName);
                    saveJavaFile("./" + inner.clazzName + ".class", bs);
                    codecLoader.define(innerName, bs, 0, bs.length);
                }
            }
        } catch (Throwable e) {
            try {
                if (codecLoader.loadClass(decoderName) != null) {
                    return codecLoader.loadClass(decoderName);
                }
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException("generateDecoder "
                        + cls.getName() + " error", ex);
            }
            throw new RuntimeException("generateDecoder "
                    + cls.getName() + " error", e);
        }

        return decoderCls;
    }

    public static final JsonCodecRegister instance() {
        return JsonCodecRegister.SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final JsonCodecRegister INSTANCE = new JsonCodecRegister();
    }

    class JsonCodecLoader extends ClassLoader {

        public JsonCodecLoader(ClassLoader parent) {
            super(parent);
        }

        public Class define(String className, byte[] bs, int offset, int len) {
            return super.defineClass(className, bs, offset, len);
        }
    }
}
