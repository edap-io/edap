/*
 * Copyright 2022 The edap Project
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

package io.edap.beanconvert;

import io.edap.util.CryptUtil;
import io.edap.util.internal.GeneratorClassInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.util.AsmUtil.saveJavaFile;
import static io.edap.util.AsmUtil.toLangName;

/**
 * Bean转换器的注册器，管理Bean转换器的注册生成等操作
 */
public class ConvertorRegister {

    static final ConcurrentHashMap<String, AbstractConvertor> CONVERTORS = new ConcurrentHashMap<>();

    static final ConcurrentHashMap<String, String> LIST_CONVERTORS = new ConcurrentHashMap<>();
    static final List<String> LIST_CONVERTOR_NAMES = new ArrayList<>();

    private final ReentrantLock lock = new ReentrantLock();

    private final ReentrantLock list_lock = new ReentrantLock();

    private ConvertorLoader convertorLoader;

    private ConvertorRegister() {
        convertorLoader = new ConvertorLoader(this.getClass().getClassLoader());
        initConvertors();
    }

    private void initConvertors() {

    }

    public static String getConvertorName(Class orignalCls, Class destCls) {
        return "ebc." + orignalCls.getPackage().getName() + ".Convertor" + CryptUtil.md5(orignalCls.getName() + "_" + destCls.getName());
        //return toLangName("ebc/io/edap/x/beanconvert/test/CarToCarDtoConvertor");
    }

    /**
     * 映射配置有变更时清楚已生成的所有转换器对象
     */
    public void clearConvertors() {
        CONVERTORS.clear();
        initConvertors();
        convertorLoader = new ConvertorLoader(this.getClass().getClassLoader());
    }

    public String createListConvert(Class<?> orignalClass, Class<?> destlClass) {
        try {
            list_lock.lock();
            String name = getListConvertorName(orignalClass, destlClass);
            String converorName = LIST_CONVERTORS.get(name);
            if (converorName != null) {
                return converorName;
            }
            converorName = LIST_CONVERTORS.get(name);
            if (converorName == null) {
                converorName = generateListConvertorClass(orignalClass, destlClass);
                if (converorName != null) {
                    LIST_CONVERTORS.put(name, converorName);
                }
            }
            return converorName;
        } finally {
            list_lock.unlock();
        }
    }

    public String getListConvertorName(Class<?> orignalClass, Class<?> destlClass) {
        String key = orignalClass.getName() + "->" + destlClass.getName();
        int index = LIST_CONVERTOR_NAMES.indexOf(key);
        if (index == -1) {
            LIST_CONVERTOR_NAMES.add(key);
            return "ebc.io.edap.beanconvert.list.ListConvertor_" + LIST_CONVERTOR_NAMES.size();
        } else {
            return "ebc.io.edap.beanconvert.list.ListConvertor_" + index;
        }
    }

    public AbstractConvertor getConvertor(Class<?> orignalClass, Class<?> destlClass) {
        String key = getConvertorName(orignalClass, destlClass);
        AbstractConvertor convertor = CONVERTORS.get(key);
        if (convertor != null) {
            return convertor;
        }
        try {
            lock.lock();
            convertor = CONVERTORS.get(key);
            if (convertor == null) {
                convertor = generateConvertor(orignalClass, destlClass);
                if (convertor != null) {
                    CONVERTORS.put(key, convertor);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("generateConvertor " + key
                    + " error", e);
        } finally {
            lock.unlock();
        }
        return convertor;
    }

    private String generateListConvertorClass(Class<?> orignalClass, Class<?> destlClass) {
        ListConvertorGenerator generator = new ListConvertorGenerator(orignalClass, destlClass, null);
        String codecName = toLangName(getListConvertorName(orignalClass, destlClass));
        try {
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            //saveJavaFile("./" + gci.clazzName + ".class", bs);
            convertorLoader.define(codecName, bs, 0, bs.length);
            return codecName;
        } catch (Exception e) {
            try {
                if (convertorLoader.loadClass(codecName) != null) {
                    convertorLoader.loadClass(codecName);
                    return codecName;
                }
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException("generateEncoder "
                        + codecName + " error", ex);
            }
            throw new RuntimeException("generateEncoder "
                    + codecName + " error", e);
        }
    }

    private AbstractConvertor generateConvertor(Class<?> orignalClass, Class<?> destlClass) {
        AbstractConvertor convertor = null;
        Class convertorCls = generateConvertorClass(orignalClass, destlClass);
        if (convertorCls != null) {
            try {
                convertor = (AbstractConvertor) convertorCls.newInstance();
            } catch (InstantiationException | IllegalAccessException ex) {
                throw new RuntimeException("generateEncoder "
                        + orignalClass.getName() + "->" + destlClass.getName() + " error", ex);
            }
        }
        return convertor;
    }

    private Class generateConvertorClass(Class<?> orignalClass, Class<?> destlClass) {
        ConvertorGenerator generator = new ConvertorGenerator(orignalClass, destlClass, null);
        String codecName = toLangName(getConvertorName(orignalClass, destlClass));
        Class encoderCls;
        try {
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            saveJavaFile("./" + gci.clazzName + ".class", bs);
            encoderCls = convertorLoader.define(codecName, bs, 0, bs.length);
        } catch (Exception e) {
            try {
                if (convertorLoader.loadClass(codecName) != null) {
                    return convertorLoader.loadClass(codecName);
                }
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException("generateEncoder "
                        + codecName + " error", ex);
            }
            throw new RuntimeException("generateEncoder "
                    + codecName + " error", e);
        }
        return encoderCls;
    }

    public static final ConvertorRegister instance() {
        return ConvertorRegister.SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final ConvertorRegister INSTANCE = new ConvertorRegister();
    }

    class ConvertorLoader extends ClassLoader {

        public ConvertorLoader(ClassLoader parent) {
            super(parent);
        }

        public Class define(String className, byte[] bs, int offset, int len) {
            return super.defineClass(className, bs, offset, len);
        }
    }
}
