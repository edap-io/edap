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

import io.edap.protobuf.internal.GeneratorClassInfo;
import io.edap.protobuf.ProtoBuf.EncodeType;
import io.edap.protobuf.ProtoBufWriter.WriteOrder;
import io.edap.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;


import static io.edap.util.AsmUtil.toInternalName;
import static io.edap.util.AsmUtil.toLangName;
import static io.edap.util.CollectionUtils.isEmpty;

/**
 * ProtoBuf编解码器的注册器，负责统一注册和获取指定Class的编解码等功能
 */
public enum ProtoBufCodecRegister {

    INSTANCE;

    private final Map<Class, ProtoBufEncoder> encoders  = new HashMap<>();
    private final Map<Class, ProtoBufEncoder> rencoders = new HashMap<>();
    private final Map<Type, ProtoBufDecoder>  decoders  = new HashMap<>();
    private final Map<Type, Class> mapEncoders     = new HashMap<>();
    private final List<Type>       mapEncoderTypes = new ArrayList<>();
    private final ProtoCodecLoader encoderLoader   = new ProtoCodecLoader(this.getClass().getClassLoader());
    private final ReentrantLock    lock            = new ReentrantLock();

    private ProtoPersister protoPersister;

    public void setProtoPersister(ProtoPersister protoPersister) {
        this.protoPersister = protoPersister;
    }

    public ProtoPersister getProtoPersister() {
        return this.protoPersister;
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
        try {
            lock.lock();
            encoder = encoders.get(msgCls);
            if (encoder == null) {
                encoder = generateEncoder(msgCls, WriteOrder.SEQUENTIAL);
                if (encoder != null) {
                    encoders.put(msgCls, encoder);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("generateEncoder " + msgCls.getName()
                    + " error", e);
        } finally {
            lock.unlock();
        }
        return encoder;
    }

    public ProtoBufEncoder getEncoder(Class msgCls, WriteOrder writeOrder) {
        ProtoBufEncoder encoder;
        if (writeOrder == WriteOrder.SEQUENTIAL) {
            return getEncoder(msgCls);
        }
        encoder = rencoders.get(msgCls);
        if (encoder != null) {
            return encoder;
        }
        try {
            lock.lock();
            encoder = rencoders.get(msgCls);
            if (encoder == null) {
                encoder = generateEncoder(msgCls, WriteOrder.REVERSE);
                if (encoder != null) {
                    rencoders.put(msgCls, encoder);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("generateEncoder " + msgCls.getName()
                    + " error", e);
        } finally {
            lock.unlock();
        }
        return encoder;
    }

    public ProtoBufDecoder getDecoder(Class msgCls) {
        ProtoBufDecoder decoder = decoders.get(msgCls);
        if (decoder != null) {
            return decoder;
        }
        try {
            lock.lock();
            decoder = decoders.get(msgCls);
            if (decoder == null) {
                decoder = generateDecoder(msgCls);
                if (decoder != null) {
                    decoders.put(msgCls, decoder);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("generateDecoder " + msgCls.getName()
                    + " error", e);
        } finally {
            lock.unlock();
        }
        return decoder;
    }

    public Class generateMapEntryClass(Type mapType) {
        Class mapEntryCls = mapEncoders.get(mapType);
        if (mapEntryCls != null) {
            return mapEntryCls;
        }
        String mapEntryName = "";
        try {
            lock.lock();
            int index = mapEncoderTypes.size();
            mapEntryName = "io.edap.protobuf.encoder.mapentry.MapEntry_" + index;
            MapEntryGenerator meg = new MapEntryGenerator(
                    toInternalName(mapEntryName), mapType);
            byte[] bs = meg.getEntryBytes();
            //saveJavaFile("./" + toInternalName(mapEntryName), bs);
            mapEntryCls = encoderLoader.define(mapEntryName, bs, 0, bs.length);
            if (mapEntryCls != null) {
                mapEncoders.put(mapType, mapEntryCls);
                mapEncoderTypes.add(mapType);
            }
        } catch (Exception e) {
            try {
                mapEntryCls = encoderLoader.loadClass(mapEntryName);
                if (mapEntryCls != null) {
                    mapEncoders.put(mapType, mapEntryCls);
                    mapEncoderTypes.add(mapType);
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


    private ProtoBufEncoder generateEncoder(Class cls, WriteOrder writeOrder) {
        ProtoBufEncoder codec = null;
        Class encoderCls = generateEncoderClass(cls, writeOrder);
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

    private ProtoBufDecoder generateDecoder(Class cls) {
        ProtoBufDecoder codec = null;
        Class decoderCls = generateDecoderClass(cls);
        if (decoderCls != null) {
            try {
                codec = (ProtoBufDecoder)decoderCls.newInstance();
            } catch (InstantiationException | IllegalAccessException ex) {
                throw new RuntimeException("generateDecoder "
                        + cls.getName() + " error", ex);
            }
        }
        return codec;
    }

    private Class generateEncoderClass(Class cls, WriteOrder writeOrder) {
        Class encoderCls;
        String encoderName = ProtoBufEncoderGenerator.getEncoderName(cls, EncodeType.STANDARD, writeOrder);
        try {
            ProtoBufEncoderGenerator generator = new ProtoBufEncoderGenerator(cls, EncodeType.STANDARD, writeOrder);
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            //saveJavaFile("./" + gci.clazzName + ".class", bs);
            encoderCls = encoderLoader.define(encoderName, bs, 0, bs.length);
            if (!CollectionUtils.isEmpty(gci.inners)) {
                for (GeneratorClassInfo inner : gci.inners) {
                    bs = inner.clazzBytes;
                    String innerName = toLangName(inner.clazzName);
                    encoderLoader.define(innerName, bs, 0, bs.length);
                }
            }
        } catch (Exception e) {
            try {
                if (encoderLoader.loadClass(encoderName) != null) {
                    return encoderLoader.loadClass(encoderName);
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

    public static void saveJavaFile(String javaFilePath, byte[] data)
            throws IOException {
        File f = new File(javaFilePath);
        if (f.exists()) {
            Files.delete(f.toPath());
        } else {
            if (!f.getParentFile().exists()) {
                f.getParentFile().mkdirs();
            }
        }

        try (RandomAccessFile java = new RandomAccessFile(javaFilePath, "rw")) {
            java.write(data);
        }
    }

    private Class generateDecoderClass(Class cls) {
        Class decoderCls;
        String decoderName = ProtoBufDecoderGenerator.getDecoderName(cls, EncodeType.STANDARD);
        try {
            ProtoBufDecoderGenerator generator = new ProtoBufDecoderGenerator(cls, EncodeType.STANDARD);
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            //saveJavaFile("./" + gci.clazzName + ".class", bs);
            decoderCls = encoderLoader.define(decoderName, bs, 0, bs.length);
            if (!isEmpty(gci.inners)) {
                for (GeneratorClassInfo inner : gci.inners) {
                    bs = inner.clazzBytes;
                    String innerName = toLangName(inner.clazzName);
                    //saveJavaFile("./" + inner.clazzName + ".class", bs);
                    encoderLoader.define(innerName, bs, 0, bs.length);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                if (encoderLoader.loadClass(decoderName) != null) {
                    return encoderLoader.loadClass(decoderName);
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

    class ProtoCodecLoader extends ClassLoader {

        public ProtoCodecLoader(ClassLoader parent) {
            super(parent);
        }

        public Class define(String className, byte[] bs, int offset, int len) {
            return super.defineClass(className, bs, offset, len);
        }
    }
}