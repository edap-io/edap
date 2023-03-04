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

import io.edap.log.converter.BaseTextConverter;
import io.edap.log.converter.TextConverter;
import io.edap.util.internal.GeneratorClassInfo;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.edap.log.helps.TextEncoderGenerator.getConverterName;
import static io.edap.util.AsmUtil.saveJavaFile;

public class TextConverterFactory {

    private static final Map<String, TextConverter> TEXT_CONVERTER_MAP = new ConcurrentHashMap<>();

    private static TextEncoderLoader TEXT_ENCODER_LOADER = new TextEncoderLoader(TextConverterFactory.class.getClassLoader());

    public static TextConverter getTextConverter(String text) {
        int len = text.getBytes(StandardCharsets.UTF_8).length;
        TextConverter converter = TEXT_CONVERTER_MAP.get(text);
        if (converter != null) {
            return converter;
        }
        if (len > 4) {
            converter = new BaseTextConverter(text);
            TextConverter oldConverter = TEXT_CONVERTER_MAP.putIfAbsent(text, converter);
            if (oldConverter != null) {
                converter = oldConverter;
            }
        } else {
            TextEncoderGenerator generator = new TextEncoderGenerator(text);
            GeneratorClassInfo gci = null;
            try {
                gci = generator.getClassInfo();
                String converterName = getConverterName(text);
                byte[] bs = gci.clazzBytes;
                saveJavaFile("./" + gci.clazzName + ".class", bs);
                Class converterCls = TEXT_ENCODER_LOADER.define(converterName, bs, 0, bs.length);
                Constructor constructor = converterCls.getConstructor(new Class[0]);
                converter = (TextConverter) constructor.newInstance(new Object[0]);
                TEXT_CONVERTER_MAP.put(text, converter);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            } catch (InstantiationException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }

        }
        return converter;
    }

    static class TextEncoderLoader extends ClassLoader {

        public TextEncoderLoader(ClassLoader parent) {
            super(parent);
        }

        public Class define(String className, byte[] bs, int offset, int len) {
            return super.defineClass(className, bs, offset, len);
        }
    }
}
