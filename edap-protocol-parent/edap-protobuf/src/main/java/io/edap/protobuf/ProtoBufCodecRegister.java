/*
 * Copyright 2020 The edap Project
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

package io.edap.protobuf;

import io.edap.protobuf.model.ProtoBufOption;
import io.edap.util.CollectionUtils;
import io.edap.util.internal.GeneratorClassInfo;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;


import static io.edap.protobuf.ProtoBufDecoderGenerator.getDecoderName;
import static io.edap.protobuf.ProtoBufEncoderGenerator.getEncoderName;
import static io.edap.protobuf.util.ProtoUtil.*;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.getClassMethods;
import static io.edap.util.CollectionUtils.isEmpty;

/**
 * ProtoBuf编解码器的注册器，负责统一注册和获取指定Class的编解码等功能
 */
public enum ProtoBufCodecRegister {

    INSTANCE;

    private final Map<Class, ProtoBufEncoder> encoders  = new HashMap<>();
    private final Map<Class, ProtoBufEncoder> fencoders = new HashMap<>();
    private final Map<Type, ProtoBufDecoder>  decoders  = new HashMap<>();

    private final Map<Type, ProtoBufDecoder>  fdecoders  = new HashMap<>();
    private final Map<Type, Class> mapEncoders     = new HashMap<>();
    private final Map<Type, MapEntryEncoder> mapEntryEncoders = new HashMap<>();
    private final Map<Type, MapEntryEncoder> mapEntryFastEncoders = new HashMap<>();
    private final Map<Type, MapEntryDecoder> mapEntryDecoders = new HashMap<>();
    private final Map<Type, MapEntryDecoder> mapEntryFastDecoders = new HashMap<>();

    private final Map<Type, MapDecoder> mapDecoders = new HashMap<>();
    private final Map<Type, MapDecoder> mapFastDecoders = new HashMap<>();

    private final Map<Type, Class> fmapEncoders     = new HashMap<>();
    private final Map<ClassLoader, ClassDefiner> encoderLoaders   = new HashMap<>();
    private final Map<Type, ReentrantLock>   locks = new HashMap<>();
    private final Map<Type, ReentrantLock>   mapEntryLocks = new HashMap<>();
    private final Map<Type, ReentrantLock>   mapDecoderLocks = new HashMap<>();

    private ProtoPersister protoPersister;

    public void setProtoPersister(ProtoPersister protoPersister) {
        this.protoPersister = protoPersister;
    }

    public ProtoPersister getProtoPersister() {
        return this.protoPersister;
    }

    private ReentrantLock getLock(Type msgCls) {
        ReentrantLock lock = locks.get(msgCls);
        if (lock == null) {
            lock = new ReentrantLock();
            ReentrantLock old = locks.putIfAbsent(msgCls, lock);
            if (old != null) {
                lock = old;
            }
        }
        return lock;
    }

    /**
     * 获取指定Class的ProtoBuf的编码器实现，ProtoBufWrite的实现为默认实现，写入数据是从前向后顺序写。该方式在编码 length+data
     * 这类的编码时，由于需要先写data后才能确认长度，所以需要多一次的内存copy效率一般。
     * @param msgCls 给定需要编码的JavaBean的Class对象
     * @return
     */
    public ProtoBufEncoder getEncoder(Class msgCls) {
        ProtoBufEncoder encoder = encoders.get(msgCls);
        if (encoder != null) {
            return encoder;
        }
        ReentrantLock lock = getLock(msgCls);
        try {
            lock.lock();
            encoder = encoders.get(msgCls);
            if (encoder == null) {
                encoder = generateEncoder(msgCls, new ProtoBufOption());
                if (encoder != null) {
                    encoders.put(msgCls, encoder);
                }
            }
        } catch (Throwable e) {
            throw new RuntimeException("generateEncoder " + msgCls.getName()
                    + " error", e);
        } finally {
            lock.unlock();
        }
        return encoder;
    }

    public ProtoBufEncoder getEncoder(Class msgCls, ProtoBufOption option) {
        if (option == null || CodecType.FAST != option.getCodecType()) {
            return getEncoder(msgCls);
        }
        ProtoBufEncoder encoder;
        encoder = fencoders.get(msgCls);
        if (encoder != null) {
            return encoder;
        }
        ReentrantLock lock = getLock(msgCls);
        try {
            lock.lock();
            encoder = fencoders.get(msgCls);
            if (encoder == null) {
                encoder = generateEncoder(msgCls, option);
                if (encoder != null) {
                    AbstractEncoder aencoder = (AbstractEncoder)encoder;
                    aencoder.setProtoBufOption(option);
                    fencoders.put(msgCls, encoder);
                }
            }
        } catch (Throwable e) {
            throw new RuntimeException("generateEncoder " + msgCls.getName()
                    + " error", e);
        } finally {
            lock.unlock();
        }
        return encoder;
    }

    public ProtoBufDecoder getDecoder(Class msgCls) {
        ProtoBufDecoder decoder;
        decoder = decoders.get(msgCls);
        if (decoder != null) {
            return decoder;
        }
        ReentrantLock lock = getLock(msgCls);
        try {
            lock.lock();
            decoder = decoders.get(msgCls);
            if (decoder == null) {
                decoder = generateDecoder(msgCls, null);
                if (decoder != null) {
                    decoders.put(msgCls, decoder);
                }
            }
        } catch (Throwable e) {
            throw new RuntimeException("generateDecoder " + msgCls.getName()
                    + " error", e);
        } finally {
            lock.unlock();
        }
        return decoder;
    }

    public ProtoBufDecoder getDecoder(Class msgCls, ProtoBufOption option) {
        if (option == null || CodecType.FAST != option.getCodecType()) {
            return getDecoder(msgCls);
        }
        ProtoBufDecoder decoder;
        decoder = fdecoders.get(msgCls);
        if (decoder != null) {
            return decoder;
        }
        ReentrantLock lock = getLock(msgCls);
        try {
            lock.lock();
            decoder = fdecoders.get(msgCls);
            if (decoder == null) {
                decoder = generateDecoder(msgCls, option);
                if (decoder != null) {
                    fdecoders.put(msgCls, decoder);
                }
            }
        } catch (Throwable e) {
            throw new RuntimeException("generateDecoder " + msgCls.getName()
                    + " error", e);
        } finally {
            lock.unlock();
        }
        return decoder;
    }

    public MapDecoder getMapDecoder(Type mapType, Class ownerCls, ProtoBufOption option) {
        Map<Type, MapDecoder> map;
        if (option.getCodecType() == CodecType.FAST) {
            map = mapFastDecoders;
        } else {
            map = mapDecoders;
        }
        MapDecoder decoder = map.get(mapType);
        if (decoder != null) {
            return decoder;
        }
        ReentrantLock lock = mapDecoderLocks.get(mapType);
        if (lock == null) {
            lock = new ReentrantLock();
            ReentrantLock old = mapEntryLocks.putIfAbsent(mapType, lock);
            if (old != null) {
                lock = old;
            }
        }
        lock.lock();
        try {
            ClassDefiner definer;
            if (ownerCls != null) {
                definer = getCodecDefiner(ownerCls);
            } else {
                definer = getCodecDefiner(ProtoBufCodecRegister.class);
            }
            String decoderName = buildMapDecoderName(mapType, option);
            Class decoderCls = null;
            try {
                decoderCls = definer.loadClass(decoderName);
            } catch (ClassNotFoundException e) {
            }
            if (decoderCls == null) {
                MapDecoderGenerator mdeg = new MapDecoderGenerator(mapType, option);
                GeneratorClassInfo gci = mdeg.getClassInfo();
                saveJavaFile("./" + toInternalName(gci.clazzName) + ".class", gci.clazzBytes);
                try {
                    decoderCls = definer.define(decoderName, gci.clazzBytes, 0, gci.clazzBytes.length);
                } catch (Throwable e) {
                    try {
                        decoderCls = definer.loadClass(decoderName);
                    } catch (ClassNotFoundException ex) {

                    }
                }
            }
            if (decoderCls != null) {
                decoder = (MapDecoder) decoderCls.getDeclaredConstructors()[0].newInstance(new Object[0]);
                if (decoder != null) {
                    map.put(mapType, decoder);
                    return decoder;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

        return decoder;
    }

    public MapEntryDecoder getMapEntryDecoder(Type mapType, Class ownerCls, ProtoBufOption option) {
        Map<Type, MapEntryDecoder> decodes;
        if (option != null && option.getCodecType() == CodecType.FAST) {
            decodes = mapEntryFastDecoders;
        } else {
            decodes = mapEntryDecoders;
        }
        MapEntryDecoder encoder = decodes.get(mapType);
        if (encoder != null) {
            return encoder;
        }
        ReentrantLock mapEntryLock = mapEntryLocks.get(mapType);
        if (mapEntryLock == null) {
            mapEntryLock = new ReentrantLock();
            ReentrantLock old = mapEntryLocks.putIfAbsent(mapType, mapEntryLock);
            if (old != null) {
                mapEntryLock = old;
            }
        }
        mapEntryLock.lock();
        try {
            ClassDefiner definer;
            if (ownerCls != null) {
                definer = getCodecDefiner(ownerCls);
            } else {
                definer = getCodecDefiner(ProtoBufCodecRegister.class);
            }
            String decoderName = buildMapEntryDecoderName(mapType, option);
            Class encoderCls = null;
            try {
                encoderCls = definer.loadClass(decoderName);
            } catch (ClassNotFoundException e) {
            }
            if (encoderCls == null) {
                MapEntryDecoderGenerator meeg = new MapEntryDecoderGenerator(mapType, option);
                GeneratorClassInfo gci = meeg.getClassInfo();
                saveJavaFile("./" + toInternalName(gci.clazzName) + ".class", gci.clazzBytes);
                try {
                    encoderCls = definer.define(decoderName, gci.clazzBytes, 0, gci.clazzBytes.length);
                } catch (Throwable e) {
                    try {
                        encoderCls = definer.loadClass(decoderName);
                    } catch (ClassNotFoundException ex) {

                    }
                }
            }
            if (encoderCls != null) {
                encoder = (MapEntryDecoder) encoderCls.getDeclaredConstructors()[0].newInstance(new Object[0]);
                if (encoder != null) {
                    decodes.put(mapType, encoder);
                    return encoder;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } finally {
            mapEntryLock.unlock();
        }
        return null;
    }

    public MapEntryEncoder getMapEntryEncoder(Type mapType, Class ownerCls, ProtoBufOption option) {
        Map<Type, MapEntryEncoder> encodes;
        if (option != null && option.getCodecType() == CodecType.FAST) {
            encodes = mapEntryFastEncoders;
        } else {
            encodes = mapEntryEncoders;
        }
        MapEntryEncoder encoder = encodes.get(mapType);
        if (encoder != null) {
            return encoder;
        }
        ReentrantLock mapEntryLock = mapEntryLocks.get(mapType);
        if (mapEntryLock == null) {
            mapEntryLock = new ReentrantLock();
            ReentrantLock old = mapEntryLocks.putIfAbsent(mapType, mapEntryLock);
            if (old != null) {
                mapEntryLock = old;
            }
        }
        mapEntryLock.lock();
        try {
            ClassDefiner definer;
            if (ownerCls != null) {
                definer = getCodecDefiner(ownerCls);
            } else {
                definer = getCodecDefiner(ProtoBufCodecRegister.class);
            }
            String encoderName = buildMapEntryEncoderName(mapType, option);
            Class encoderCls = null;
            try {
                encoderCls = definer.loadClass(encoderName);
            } catch (ClassNotFoundException e) {
            }
            if (encoderCls == null) {
                MapEntryEncoderGenerator meeg = new MapEntryEncoderGenerator(mapType, option);
                GeneratorClassInfo gci = meeg.getClassInfo();
                saveJavaFile("./" + toInternalName(gci.clazzName) + ".class", gci.clazzBytes);
                try {
                    encoderCls = definer.define(encoderName, gci.clazzBytes, 0, gci.clazzBytes.length);
                } catch (Throwable e) {
                    try {
                        encoderCls = definer.loadClass(encoderName);
                    } catch (ClassNotFoundException ex) {

                    }
                }
            }
            if (encoderCls != null) {
                encoder = (MapEntryEncoder) encoderCls.getDeclaredConstructors()[0].newInstance(new Object[0]);
                if (encoder != null) {
                    encodes.put(mapType, encoder);
                    return encoder;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } finally {
            mapEntryLock.unlock();
        }
        return null;
    }

    public Class generateMapEntryClass(Type mapType, Class ownerCls) {
        Class mapEntryCls = mapEncoders.get(ownerCls);
        if (mapEntryCls != null) {
            return mapEntryCls;
        }
        String mapEntryName = "";
        ClassDefiner definer = getCodecDefiner(ownerCls);
        ReentrantLock lock = getLock(mapType);
        try {
            lock.lock();
            mapEntryName = buildMapEncodeName(mapType, null);
            MapEntryGenerator meg = new MapEntryGenerator(
                    toInternalName(mapEntryName), mapType);
            byte[] bs = meg.getEntryBytes();
            saveJavaFile("./" + toInternalName(mapEntryName) + ".class", bs);
            mapEntryCls = definer.define(mapEntryName, bs, 0, bs.length);
            if (mapEntryCls != null) {
                mapEncoders.put(ownerCls, mapEntryCls);
            }
        } catch (Throwable e) {
            try {
                mapEntryCls = definer.loadClass(mapEntryName);
                if (mapEntryCls != null) {
                    mapEncoders.put(ownerCls, mapEntryCls);
                    return mapEntryCls;
                }
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException("generateMapEntryClass "
                        + mapType.getTypeName() + " error", ex);
            }
            throw new RuntimeException("generateMapEntryClass "
                    + mapType.getTypeName()+ " error", e);
        } finally {
            lock.unlock();
        }
        return mapEntryCls;
    }

    public Class generateMapEntryClass(Type mapType, ProtoBufOption option, Class ownerCls) {
        if (option == null || CodecType.FAST != option.getCodecType()) {
            return generateMapEntryClass(mapType, ownerCls);
        }
        Class mapEntryCls = fmapEncoders.get(mapType);
        if (mapEntryCls != null) {
            return mapEntryCls;
        }
        String mapEntryName = "";
        ClassDefiner definer = getCodecDefiner(ownerCls);
        ReentrantLock lock = getLock(mapType);
        try {
            lock.lock();
            mapEntryName = buildMapEncodeName(mapType, option);
            MapEntryGenerator meg = new MapEntryGenerator(
                    toInternalName(mapEntryName), mapType);
            byte[] bs = meg.getEntryBytes();
            saveJavaFile("./" + toInternalName(mapEntryName) + ".class", bs);
            mapEntryCls = definer.define(mapEntryName, bs, 0, bs.length);
            if (mapEntryCls != null) {
                fmapEncoders.put(mapType, mapEntryCls);
            }
        } catch (Throwable e) {
            try {
                mapEntryCls = definer.loadClass(mapEntryName);
                if (mapEntryCls != null) {
                    mapEncoders.put(mapType, mapEntryCls);
                    return mapEntryCls;
                }
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException("generateMapEntryClass "
                        + mapType.getTypeName() + " error", ex);
            }
            throw new RuntimeException("generateMapEntryClass "
                    + mapType.getTypeName()+ " error", e);
        } finally {
            lock.unlock();
        }
        return mapEntryCls;
    }


    private ProtoBufEncoder generateEncoder(Class cls, ProtoBufOption option) {
        ProtoBufEncoder codec = null;
        Class encoderCls = null;
        try {
            ClassDefiner definer = getCodecDefiner(cls);
            encoderCls = definer.loadClass(getEncoderName(cls, option));
        } catch (Throwable t) {
            encoderCls = generateEncoderClass(cls, option);
        }
        if (encoderCls != null) {
            try {
                codec = (ProtoBufEncoder)encoderCls.newInstance();
            } catch (InstantiationException | IllegalAccessException ex) {
                throw new RuntimeException("generateEncoder "
                        + cls.getName() + " error", ex);
            }
        }
        return codec;
    }

    private ProtoBufDecoder generateDecoder(Class cls, ProtoBufOption option) {
        ProtoBufDecoder codec = null;
        Class decoderCls = null;
        try {
            ClassDefiner definer = getCodecDefiner(cls);
            decoderCls = definer.loadClass(getDecoderName(cls, option));
        } catch (Throwable t) {
            decoderCls = generateDecoderClass(cls, option);
        }
        if (decoderCls != null) {
            try {
                codec = (ProtoBufDecoder) decoderCls.newInstance();
            } catch (InstantiationException | IllegalAccessException ex) {
                throw new RuntimeException("generateDecoder "
                        + cls.getName() + " error", ex);
            }
        }
        return codec;
    }

    private Class generateEncoderClass(Class cls, ProtoBufOption otpion) {
        Class encoderCls;
        String encoderName = getEncoderName(cls, otpion);
        ClassDefiner definer = getCodecDefiner(cls);
        try {
            ProtoBufEncoderGenerator generator = new ProtoBufEncoderGenerator(cls, otpion);
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            saveJavaFile("./" + gci.clazzName + ".class", bs);
            encoderCls = definer.define(encoderName, bs, 0, bs.length);
            if (!CollectionUtils.isEmpty(gci.inners)) {
                for (GeneratorClassInfo inner : gci.inners) {
                    bs = inner.clazzBytes;
                    String innerName = toLangName(inner.clazzName);
                    definer.define(innerName, bs, 0, bs.length);
                }
            }
        } catch (Throwable e) {
            try {
                if (definer.loadClass(encoderName) != null) {
                    return definer.loadClass(encoderName);
                }
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException("generateEncoder "
                        + cls.getName() + " error", ex);
            }
            throw new RuntimeException("generateEncoder "
                    + cls.getName() + " error", e);
        }
        return encoderCls;
    }

    private Class defineClass(ClassLoader loader, String name, byte[] bs, int start, int len) {
        List<Method> methods = getClassMethods(loader.getClass());
        if (CollectionUtils.isEmpty(methods)) {
            throw new RuntimeException(loader + " hasn't define method");
        }
        for (Method m : methods) {
            if ("defineClass".equals(m.getName())) {
                Class<?>[] types = m.getParameterTypes();
                if (types.length == 4 && types[0].getName().equals("java.lang.String")
                        && types[1].getName().equals("[B") && types[2].getName().equals("int")
                        && types[3].getName().equals("int")) {
                    m.setAccessible(true);
                    try {
                        return (Class)m.invoke(loader, name, bs, start, len);
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        throw new RuntimeException(loader + " hasn't define method");
    }

    private Class generateDecoderClass(Class cls, ProtoBufOption option) {
        Class decoderCls;
        String decoderName = getDecoderName(cls, option);
        ClassDefiner definer = getCodecDefiner(cls);
        try {
            ProtoBufDecoderGenerator generator = new ProtoBufDecoderGenerator(cls, option);
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            saveJavaFile("./" + gci.clazzName + ".class", bs);
            decoderCls = definer.define(decoderName, bs, 0, bs.length);
            if (!isEmpty(gci.inners)) {
                for (GeneratorClassInfo inner : gci.inners) {
                    bs = inner.clazzBytes;
                    String innerName = toLangName(inner.clazzName);
                    saveJavaFile("./" + inner.clazzName + ".class", bs);
                    definer.define(innerName, bs, 0, bs.length);
                }
            }
        } catch (Throwable e) {
            try {
                if (definer.loadClass(decoderName) != null) {
                    return definer.loadClass(decoderName);
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

    private ClassDefiner getCodecDefiner(Type type) {
        Class cls;
        if (type instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType) type;
            cls = (Class) ptype.getRawType();
        } else {
            cls = (Class)type;
        }
        ClassLoader cl = cls.getClassLoader();
        ClassDefiner definer = encoderLoaders.get(cl);
        if (definer == null) {
            definer = new ClassDefiner(cl);
            encoderLoaders.put(cl, definer);
        }
        return definer;
    }

    class ClassDefiner {

        private ClassLoader loader;
        private Method defineMethod;

        public ClassDefiner(ClassLoader cl) {
            this.loader = cl;
            this.defineMethod = getClassDefineMethod(cl);
        }

        public Class define(String className, byte[] bs, int offset, int len) {
            if (defineMethod == null) {
                throw  new RuntimeException(loader + " hasn't defineClass(String name, byte[] bs, int start, int len)");
            }
            try {
                return (Class)defineMethod.invoke(loader, className, bs, offset, len);
            } catch (Throwable t) {
                throw new RuntimeException(loader + " define class " + className + " error", t);
            }
        }

        public Class loadClass(String name) throws ClassNotFoundException {
            return loader.loadClass(name);
        }

        private Method getClassDefineMethod(ClassLoader loader) {
            List<Method> methods = getClassMethods(loader.getClass());
            if (CollectionUtils.isEmpty(methods)) {
                return null;
            }
            for (Method m : methods) {
                if ("defineClass".equals(m.getName())) {
                    Class<?>[] types = m.getParameterTypes();
                    if (types.length == 4 && types[0].getName().equals("java.lang.String")
                            && types[1].getName().equals("[B") && types[2].getName().equals("int")
                            && types[3].getName().equals("int")) {
                        m.setAccessible(true);
                        try {
                            return m;
                        } catch (Throwable e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
            return null;
        }
    }
}