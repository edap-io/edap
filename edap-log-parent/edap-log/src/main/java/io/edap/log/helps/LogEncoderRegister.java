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

package io.edap.log.helps;

import io.edap.log.Encoder;
import io.edap.util.internal.GeneratorClassInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.edap.log.helps.EncoderGenerator.getEncoderName;
import static io.edap.util.AsmUtil.saveJavaFile;
import static io.edap.util.AsmUtil.toLangName;
import static io.edap.util.CollectionUtils.isEmpty;

public class LogEncoderRegister {

    private static final Map<String, Encoder> ENCODER_MAP = new ConcurrentHashMap<>();

    private final LogEncodecLoader codecLoader = new LogEncodecLoader(this.getClass().getClassLoader());

    public Encoder getEncoder(String format) {
        String key = format;
        Encoder encoder = ENCODER_MAP.get(key);
        if (encoder == null) {
            encoder = generateEncoder(format);
            ENCODER_MAP.put(key, encoder);
        }
        return encoder;
    }

    private Encoder generateEncoder(String format) {
        Encoder codec = null;
        Class encoderCls = generateEncoderClass(format);
        if (encoderCls != null) {
            try {
                codec = (Encoder)encoderCls.newInstance();
            } catch (InstantiationException | IllegalAccessException ex) {
                throw new RuntimeException("generateDecoder "
                        + format + " error", ex);
            }
        }
        return codec;
    }

    private Class generateEncoderClass(String format) {
        Class encoderCls = null;
        String encoderName = getEncoderName(format);
        try {
            encoderCls = Class.forName(encoderName);
            return encoderCls;
        } catch (ClassNotFoundException e) {
            System.err.println(e.getMessage());
        }
        try {
            EncoderGenerator generator = new EncoderGenerator(format);
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
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
                        + format + " error", ex);
            }
            throw new RuntimeException("generateDecoder "
                    + format + " error", e);
        }

        return encoderCls;
    }

    public static final LogEncoderRegister instance() {
        return LogEncoderRegister.SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final LogEncoderRegister INSTANCE = new LogEncoderRegister();
    }

    class LogEncodecLoader extends ClassLoader {

        public LogEncodecLoader(ClassLoader parent) {
            super(parent);
        }

        public Class define(String className, byte[] bs, int offset, int len) {
            return super.defineClass(className, bs, offset, len);
        }
    }
}
