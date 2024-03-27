package io.edap.eproto;

import io.edap.protobuf.CodecType;
import io.edap.protobuf.ProtoPersister;
import io.edap.util.CollectionUtils;
import io.edap.util.internal.GeneratorClassInfo;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.eproto.EprotoEncoderGenerator.getEncoderName;
import static io.edap.protobuf.util.ProtoUtil.buildMapEncodeName;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.AsmUtil.toInternalName;

public class EprotoCodecRegister {

    private final Map<Class, EprotoEncoder> encoders  = new HashMap<>();
    private final Map<Type, EprotoDecoder>  decoders  = new HashMap<>();

    private final Map<Type, Class> mapEncoders     = new HashMap<>();

    private final Map<ClassLoader, EprotoCodecLoader> encoderLoaders   = new HashMap<>();
    private final ReentrantLock lock            = new ReentrantLock();

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
    public EprotoEncoder getEncoder(Class msgCls) {
        EprotoEncoder encoder = encoders.get(msgCls);
        if (encoder != null) {
            return encoder;
        }
        try {
            lock.lock();
            encoder = encoders.get(msgCls);
            if (encoder == null) {
                encoder = generateEncoder(msgCls);
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

    private EprotoEncoder generateEncoder(Class cls) {
        EprotoEncoder codec = null;
        Class encoderCls = null;
        try {
            EprotoCodecLoader encoderLoader = getCodecLoader(cls);
            encoderCls = encoderLoader.loadClass(getEncoderName(cls));
        } catch (Throwable t) {
            encoderCls = generateEncoderClass(cls);
        }
        if (encoderCls != null) {
            try {
                codec = (EprotoEncoder)encoderCls.newInstance();
            } catch (InstantiationException | IllegalAccessException ex) {
                throw new RuntimeException("generateEncoder "
                        + cls.getName() + " error", ex);
            }
        }
        return codec;
    }

    private Class generateEncoderClass(Class cls) {
        Class encoderCls;
        String encoderName = getEncoderName(cls);
        EprotoCodecLoader encoderLoader = getCodecLoader(cls);
        try {
            EprotoEncoderGenerator generator = new EprotoEncoderGenerator(cls);
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            saveJavaFile("./" + gci.clazzName + ".class", bs);
            encoderCls = encoderLoader.define(encoderName, bs, 0, bs.length);
            if (!CollectionUtils.isEmpty(gci.inners)) {
                for (GeneratorClassInfo inner : gci.inners) {
                    bs = inner.clazzBytes;
                    String innerName = toLangName(inner.clazzName);
                    encoderLoader.define(innerName, bs, 0, bs.length);
                }
            }
        } catch (Throwable e) {
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


    public static final EprotoCodecRegister instance() {
        return EprotoCodecRegister.SingletonHolder.INSTANCE;
    }

    public Class generateMapEntryClass(Type mapType, Class ownerCls) {
        Class mapEntryCls = mapEncoders.get(mapType);
        if (mapEntryCls != null) {
            return mapEntryCls;
        }
        String mapEntryName = "";
        EprotoCodecLoader encoderLoader = getCodecLoader(ownerCls);
        try {
            lock.lock();
            mapEntryName = buildMapEncodeName(mapType, null);
            MapEntryGenerator meg = new MapEntryGenerator(
                    toInternalName(mapEntryName), mapType);
            byte[] bs = meg.getEntryBytes();
            saveJavaFile("./" + toInternalName(mapEntryName) + ".class", bs);
            mapEntryCls = encoderLoader.define(mapEntryName, bs, 0, bs.length);
            if (mapEntryCls != null) {
                mapEncoders.put(ownerCls, mapEntryCls);
            }
        } catch (Throwable e) {
            try {
                mapEntryCls = encoderLoader.loadClass(mapEntryName);
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

    private static class SingletonHolder {
        private static final EprotoCodecRegister INSTANCE = new EprotoCodecRegister();
    }

    private EprotoCodecLoader getCodecLoader(Class cls) {
        ClassLoader cl = cls.getClassLoader();
        EprotoCodecLoader loader = encoderLoaders.get(cl);
        if (loader == null) {
            loader = new EprotoCodecLoader(cl);
            encoderLoaders.put(cl, loader);
        }
        return loader;
    }

    class EprotoCodecLoader extends ClassLoader {

        public EprotoCodecLoader(ClassLoader parent) {
            super(parent);
        }

        public Class define(String className, byte[] bs, int offset, int len) {
            return super.defineClass(className, bs, offset, len);
        }
    }
}
