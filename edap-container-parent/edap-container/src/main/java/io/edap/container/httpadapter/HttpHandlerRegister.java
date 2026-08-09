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

package io.edap.container.httpadapter;

import io.edap.http.HttpHandler;
import io.edap.util.CryptUtil;
import io.edap.util.internal.GeneratorClassInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static io.edap.util.AsmUtil.saveJavaFile;
import static io.edap.util.AsmUtil.toLangName;
import static io.edap.util.CollectionUtils.isEmpty;

public class HttpHandlerRegister {

    private Map<Method, HttpHandler> HANDLER_MAP = new HashMap<>();

    private final Map<ClassLoader, HttpHandlerRegister.HttpHandlerLoader> handlerLoaders = new HashMap<>();

    private HttpHandlerRegister() {}

    public void cleanHandler(Method... methods) {
        if (methods.length == 0) {
            HANDLER_MAP.clear();
        } else {
            for (Method m : methods) {
                HANDLER_MAP.remove(m);
            }
        }
    }

    public HttpHandler getParameterHandler(Method method, Object bean, HandlerConfig handlerConfig) {
        HttpHandler handler = HANDLER_MAP.get(method);
        if (handler != null) {
            return handler;
        }
        synchronized (method) {
            handler = HANDLER_MAP.get(method);
            if (handler != null) {
                return handler;
            }
            handler = generateHandler(method, bean, handlerConfig);
            HANDLER_MAP.put(method, handler);
            return handler;
        }
    }

    private HttpHandler generateHandler(Method method, Object bean, HandlerConfig handlerConfig) {
        Class handlerCls = generateHandlerClass(method, bean, handlerConfig);
        if (handlerCls != null) {
            try {
                Constructor[] consts = handlerCls.getConstructors();
                for (Constructor c : consts) {
                    if (c.getParameterCount() == 1 && c.getParameterTypes().length == 1
                            && c.getParameterTypes()[0].getName().equals(bean.getClass().getName())) {
                        return (HttpHandler) c.newInstance(bean);
                    }
                }
            } catch (InstantiationException | IllegalAccessException ex) {
                throw new RuntimeException("generateDecoder "
                        + bean.getClass().getName() + " error", ex);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }
        return null;

    }

    private HttpHandlerRegister.HttpHandlerLoader getHandlerLoader(Class clazz) {
        ClassLoader classLoader = clazz.getClassLoader();
        HttpHandlerRegister.HttpHandlerLoader loader = handlerLoaders.get(classLoader);
        if (loader == null) {
            loader = new HttpHandlerRegister.HttpHandlerLoader(classLoader);
        }

        return loader;
    }

    public static String buildHandlerName(Method method) {
        StringBuilder name = new StringBuilder();
        name.append("ehh.");
        name.append(method.getDeclaringClass().getName().toLowerCase(Locale.ENGLISH)).append('.');
        StringBuilder ptypes = new StringBuilder();
        ptypes.append('(');
        if (method.getParameterTypes().length > 0) {
            for (Class c : method.getParameterTypes()) {
                if (ptypes.length() > 1) {
                    ptypes.append(',');
                }
                ptypes.append(c.getName());
            }
        }
        ptypes.append(')');
        name.append("HttpHandler_").append(CryptUtil.md5(ptypes.toString()));
        return name.toString();
    }

    private Class generateHandlerClass(Method method, Object bean, HandlerConfig handlerConfig) {

        Class encoderCls;
        String encoderName = buildHandlerName(method);
        HttpHandlerRegister.HttpHandlerLoader codecLoader = getHandlerLoader(bean.getClass());
        try {
            encoderCls = codecLoader.loadClass(encoderName);
            return encoderCls;
        } catch (ClassNotFoundException e) {

        }
        try {
            ParameterHandlerGenerator generator = new ParameterHandlerGenerator(bean.getClass(), method, handlerConfig);
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
                        + bean.getClass().getName() + " error", e);
            }
            throw new RuntimeException("generateDecoder "
                    + bean.getClass().getName() + " error", e);
        }

        return encoderCls;
    }

    public static final HttpHandlerRegister instance() {
        return HttpHandlerRegister.SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final HttpHandlerRegister INSTANCE = new HttpHandlerRegister();
    }

    class HttpHandlerLoader extends ClassLoader {

        public HttpHandlerLoader(ClassLoader parent) {
            super(parent);
        }

        public Class define(String className, byte[] bs, int offset, int len) {
            return super.defineClass(className, bs, offset, len);
        }
    }
}
