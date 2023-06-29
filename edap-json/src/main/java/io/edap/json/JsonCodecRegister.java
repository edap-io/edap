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
import io.edap.json.encoders.DoubleEncoder;
import io.edap.json.encoders.IntegerEncoder;
import io.edap.json.encoders.ObjectEncoder;
import io.edap.json.encoders.StringEncoder;
import io.edap.json.enums.DataType;
import io.edap.json.enums.JsonVersion;
import io.edap.util.internal.GeneratorClassInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.edap.json.util.JsonUtil.buildDecoderName;
import static io.edap.json.util.JsonUtil.buildEncoderName;
import static io.edap.util.AsmUtil.saveJavaFile;
import static io.edap.util.AsmUtil.toLangName;
import static io.edap.util.CollectionUtils.isEmpty;

public class JsonCodecRegister {



    private static final Map<String, JsonDecoder> DECODER_MAP = new ConcurrentHashMap<>();

    private static final Map<String, JsonEncoder> ENCODER_MAP = new ConcurrentHashMap<>();

    static {
        ENCODER_MAP.put(String.class.getName(), new StringEncoder());
        ENCODER_MAP.put(Integer.class.getName(), new IntegerEncoder());
        ENCODER_MAP.put(Object.class.getName(), new ObjectEncoder());
        ENCODER_MAP.put(Double.class.getName(), new DoubleEncoder());
    }

    private final JsonCodecLoader codecLoader = new JsonCodecLoader(this.getClass().getClassLoader());

    private JsonCodecRegister() {}

    public <T> JsonEncoder<T> getEncoder(Class<T> tClass) {
        String key = tClass.getName();
        JsonEncoder encoder = ENCODER_MAP.get(key);
        if (encoder == null) {
            encoder = generateEncoder(tClass);
            ENCODER_MAP.put(key, encoder);
        }
        return encoder;
    }

    public <T> JsonDecoder<T> getDecoder(Class<T> tClass, DataType dataType) {
        return getDecoder(tClass, dataType, JsonVersion.JSON);
    }

    public <T> JsonDecoder<T> getDecoder(Class<T> tClass, DataType dataType, JsonVersion version) {
        String key = tClass.getName() + "-" + dataType + "-" + version;
        JsonDecoder decoder = DECODER_MAP.get(key);
        if (decoder == null) {
            decoder = generateDecoder(tClass, dataType, version);
            DECODER_MAP.put(key, decoder);
        }

        if (decoder == null) {
            decoder = new ReflectDecoder(tClass, dataType);
            DECODER_MAP.put(key, decoder);
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

    private Class generateEncoderClass(Class cls) {
        Class encoderCls = null;
        long start = System.currentTimeMillis();
        String encoderName = buildEncoderName(cls);
        try {
            encoderCls = Class.forName(encoderName);
            return encoderCls;
        } catch (ClassNotFoundException e) {
            System.err.println(e.getMessage());
        }
        try {
            JsonEncoderGenerator generator = new JsonEncoderGenerator(cls);
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            System.out.println("generate class time: " + (System.currentTimeMillis() - start));
            saveJavaFile("./" + gci.clazzName + ".class", bs);
            encoderCls = codecLoader.define(encoderName, bs, 0, bs.length);
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
            System.err.println(e.getMessage());
        }
        try {
            JsonDecoderGenerator generator = new JsonDecoderGenerator(cls, dataType, version);
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            System.out.println("generate class time: " + (System.currentTimeMillis() - start));
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
