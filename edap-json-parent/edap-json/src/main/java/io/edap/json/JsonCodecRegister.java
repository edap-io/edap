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

import io.edap.json.decoders.MapReflectDecoder;
import io.edap.json.decoders.ReflectDecoder;
import io.edap.json.encoders.*;
import io.edap.json.enums.DataType;
import io.edap.json.enums.JsonVersion;
import io.edap.util.internal.GeneratorClassInfo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.json.util.JsonUtil.*;
import static io.edap.json.util.JsonUtil.buildMapBeanDecoderName;
import static io.edap.json.util.JsonUtil.buildMapDecoderName;
import static io.edap.util.AsmUtil.saveClassFile;
import static io.edap.util.AsmUtil.toLangName;
import static io.edap.util.CollectionUtils.isEmpty;

public class JsonCodecRegister {

    private static final Map<Class<?>, Map<String, JsonDecoder>> DECODER_MAP = new ConcurrentHashMap<>();

    private static final Map<Class<?>, MapBeanDecoder> MAP_BEAN_DECODER_MAP = new ConcurrentHashMap<>();

    private static final Map<Class<?>, JsonEncoder> ENCODER_MAP        = new HashMap<>();
    private static final Map<Type, MapEncoder>      MAP_ENCODER_MAP    = new HashMap<>();
    private static final Map<Type, Lock>            MAP_TYPE_LOCKS     = new HashMap<>();
    private static final Map<Type, JsonCodecLoader> MAP_ENCODER_LOADER = new HashMap<>();

    static {
        ENCODER_MAP.put(Boolean.class, new BooleanEncoder());
        ENCODER_MAP.put(String.class,  new StringEncoder());
        ENCODER_MAP.put(Integer.class, new IntegerEncoder());
        ENCODER_MAP.put(Long.class,    new LongEncoder());
        ENCODER_MAP.put(Object.class,  new ObjectEncoder());
        ENCODER_MAP.put(Double.class,  new DoubleEncoder());

        ENCODER_MAP.put(Date.class,    new DateEncoder());
    }

    private final Map<ClassLoader, JsonCodecLoader> codecLoaders = new HashMap<>();

    private JsonCodecRegister() {}

    public <T> JsonEncoder<T> getEncoder(Class<T> tClass) {
        JsonEncoder encoder = ENCODER_MAP.get(tClass);
        if (encoder != null) {
            return encoder;
        }
        synchronized (tClass) {
            encoder = ENCODER_MAP.get(tClass);
            if (encoder != null) {
                return encoder;
            }
            encoder = generateEncoder(tClass);
            ENCODER_MAP.put(tClass, encoder);
            return encoder;
        }
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

    /**
     * 获取 {@code Map<String,Object> → T} 解码器（{@link MapBeanDecoder}）。
     *
     * <p>首查 {@link #MAP_BEAN_DECODER_MAP}；miss 则尝试 ASM 生成（{@code MapBeanDecoderGenerator}），
     *     失败则 fallback 到 {@link MapReflectDecoder}（与 {@link ReflectDecoder} 对称）。</p>
     *
     * <p>缓存键为 {@code Class<?>} —— Map 入参已 typed，无 JSON 格式维度，
     *     不需要 {@code DataType}/{@code JsonVersion} 复合 key。</p>
     */
    public <T> MapBeanDecoder<T> getMapBeanDecoder(Class<T> tClass) {
        MapBeanDecoder decoder = MAP_BEAN_DECODER_MAP.get(tClass);
        if (decoder != null) {
            return decoder;
        }
        synchronized (tClass) {
            decoder = MAP_BEAN_DECODER_MAP.get(tClass);
            if (decoder != null) {
                return decoder;
            }
            decoder = generateMapBeanDecoder(tClass);
            MAP_BEAN_DECODER_MAP.put(tClass, decoder);
            return decoder;
        }
    }

    private MapBeanDecoder generateMapBeanDecoder(Class cls) {
        MapBeanDecoder decoder = null;
        Class decoderCls = generateMapBeanDecoderClass(cls);
        if (decoderCls != null) {
            try {
                decoder = (MapBeanDecoder) decoderCls.getDeclaredConstructors()[0].newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException ex) {
                throw new RuntimeException("generateMapBeanDecoder "
                        + cls.getName() + " error", ex);
            }
        }
        if (decoder == null) {
            decoder = new MapReflectDecoder(cls);
        }
        return decoder;
    }

    private Class generateMapBeanDecoderClass(Class cls) {
        Class decoderCls;
        String decoderName = buildMapBeanDecoderName(cls);
        try {
            decoderCls = Class.forName(decoderName);
            return decoderCls;
        } catch (ClassNotFoundException e) {
            // 未找到 → 走 ASM 生成
        }
        JsonCodecLoader codecLoader = getEncoderLoader(cls);
        try {
            MapBeanDecoderGenerator generator = new MapBeanDecoderGenerator(cls);
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            saveClassFile("./" + gci.clazzName + ".class", bs);
            decoderCls = codecLoader.define(decoderName, bs, 0, bs.length);
        } catch (Throwable e) {
            // ASM 生成失败 —— 打印原异常用于诊断（业务侧能看到根因），然后尝试 loadClass：
            //   - 若该类已被其他线程定义（极端 race），返回已定义的类
            //   - 若仍未定义，返回 null 由调用方 fallback 到 MapReflectDecoder
            // 不引入日志依赖，与 Eson.java 中已有的 e.printStackTrace() 用法一致
            e.printStackTrace();
            try {
                return codecLoader.loadClass(decoderName);
            } catch (ClassNotFoundException ex) {
                return null;
            }
        }
        return decoderCls;
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

    public <K, V> MapEncoder<K, V> getMapEncoder(Type mapType, Class ownerClass, DataType dataType) {
        MapEncoder encoder = MAP_ENCODER_MAP.get(mapType);
        if (encoder != null) {
            return encoder;
        }
        Lock lock = getMapTypeLock(mapType);
        lock.lock();
        try {
            String encoderName = buildMapDecoderName(mapType);
            JsonCodecLoader codecLoader = MAP_ENCODER_LOADER.get(mapType);
            if (codecLoader == null) {
                ClassLoader loader = getClassLoader(ownerClass);
                codecLoader = new JsonCodecLoader(loader);
                MAP_ENCODER_LOADER.put(mapType, codecLoader);
            }
            Class encoderCls;
            try {
                encoderCls = codecLoader.loadClass(encoderName);
            } catch (ClassNotFoundException e) {
                encoderCls = generateMapEncoderClass(mapType, dataType, codecLoader);
            }
            encoder = (MapEncoder)encoderCls.getDeclaredConstructors()[0].newInstance();
            if (encoder != null) {
                MAP_ENCODER_MAP.put(mapType, encoder);
            }
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
        return encoder;
    }

    private Class generateMapEncoderClass(Type mapType, DataType dataType, JsonCodecLoader loader) {
        Class encoderCls;
        String encoderName = buildMapDecoderName(mapType);
        try {
            MapEncoderGenerator generator = new MapEncoderGenerator(mapType);
            GeneratorClassInfo gci = generator.getClassInfo();

            byte[] bs = gci.clazzBytes;
            saveClassFile("./" + gci.clazzName + ".class", bs);
            encoderCls = loader.define(encoderName, bs, 0, bs.length);
            if (!isEmpty(gci.inners)) {
                for (GeneratorClassInfo inner : gci.inners) {
                    bs = inner.clazzBytes;
                    String innerName = toLangName(inner.clazzName);
                    saveClassFile("./" + inner.clazzName + ".class", bs);
                    loader.define(innerName, bs, 0, bs.length);
                }
            }
        } catch (Throwable e) {
            try {
                if (loader.loadClass(encoderName) != null) {
                    return loader.loadClass(encoderName);
                }
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException("generateEncoder "
                        + mapType.getTypeName() + " error", ex);
            }
            throw new RuntimeException("generateEncoder "
                    + mapType.getTypeName() + " error", e);
        }

        return encoderCls;
    }

    private ClassLoader getClassLoader(Class ownerClass) {
        if (ownerClass == null) {
            return JsonCodecRegister.class.getClassLoader();
        }
        return ownerClass.getClassLoader();
    }

    private Lock getMapTypeLock(Type mapType) {
        Lock lock = MAP_TYPE_LOCKS.get(mapType);
        if (lock != null) {
            return lock;
        }
        lock = new ReentrantLock();
        Lock oldLock = MAP_TYPE_LOCKS.putIfAbsent(mapType, lock);
        if (oldLock != null) {
            return lock;
        }

        return lock;
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
        Class encoderCls;
        String encoderName = buildEncoderName(cls);
        JsonCodecLoader codecLoader = getEncoderLoader(cls);
        try {
            encoderCls = codecLoader.loadClass(encoderName);
            return encoderCls;
        } catch (ClassNotFoundException e) {

        }
        try {
            JsonEncoderGenerator generator = new JsonEncoderGenerator(cls);
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            saveClassFile("./" + gci.clazzName + ".class", bs);
            encoderCls = codecLoader.define(encoderName, bs, 0, bs.length);
            if (!isEmpty(gci.inners)) {
                for (GeneratorClassInfo inner : gci.inners) {
                    bs = inner.clazzBytes;
                    String innerName = toLangName(inner.clazzName);
                    saveClassFile("./" + inner.clazzName + ".class", bs);
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
        Class decoderCls;
        String decoderName = buildDecoderName(cls, dataType, version);
        try {
            decoderCls = Class.forName(decoderName);
            return decoderCls;
        } catch (ClassNotFoundException e) {
            //e.printStackTrace();
        }
        JsonCodecLoader codecLoader = getEncoderLoader(cls);
        try {
            JsonDecoderGenerator generator = new JsonDecoderGenerator(cls, dataType, version);
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            saveClassFile("./" + gci.clazzName + ".class", bs);
            decoderCls = codecLoader.define(decoderName, bs, 0, bs.length);
            if (!isEmpty(gci.inners)) {
                for (GeneratorClassInfo inner : gci.inners) {
                    bs = inner.clazzBytes;
                    String innerName = toLangName(inner.clazzName);
                    saveClassFile("./" + inner.clazzName + ".class", bs);
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
