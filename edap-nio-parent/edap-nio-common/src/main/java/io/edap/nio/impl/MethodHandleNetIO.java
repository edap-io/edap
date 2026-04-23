/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.nio.impl;

import io.edap.nio.EdapNetIO;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static io.edap.util.ClazzUtil.getField;

public class MethodHandleNetIO implements EdapNetIO {

    protected static final MethodHandle READ0_MH;
    protected static final MethodHandle WRITE0_MH;
    protected static final MethodHandle WRITE0_MH2;
    protected static final ConstantCallSite READ_CALLSITE;
    protected static final ConstantCallSite WRITE_CALLSITE;
    protected static final ConstantCallSite WRITE_CALLSITE2;

    static {
        Class<?> fdi;
        try {
            fdi = Class.forName("sun.nio.ch.FileDispatcherImpl");


            MethodHandles.Lookup lookup = MethodHandles.lookup();

            lookup.in(fdi);
            Method read0 = getMethod(fdi, "read0", new Class[]{FileDescriptor.class, long.class, int.class});
            READ0_MH = lookup.unreflect(read0);
            READ_CALLSITE = new ConstantCallSite(READ0_MH);

            MethodHandle write0Mh = null;
            MethodHandle write0Mh2 = null;
            try {
                Method write0 = getMethod(fdi, "write0", FileDescriptor.class, long.class, int.class);
                write0Mh = lookup.unreflect(write0);
            } catch (AssertionError var7) {
                Method write0 = getMethod(fdi, "write0", FileDescriptor.class, long.class, int.class, boolean.class);
                write0Mh2 = lookup.unreflect(write0);
            }

            WRITE0_MH = write0Mh;
            if (WRITE0_MH != null) {
                WRITE_CALLSITE = new ConstantCallSite(write0Mh);
            } else {
                WRITE_CALLSITE = null;
            }
            WRITE0_MH2 = write0Mh2;
            if (WRITE0_MH2  != null) {
                WRITE_CALLSITE2 = new ConstantCallSite(write0Mh2);
            } else {
                WRITE_CALLSITE2 = null;
            }
        } catch (ClassNotFoundException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public int read(FileDescriptor fd, long address, int len) throws IOException {
        try {
            return (int)READ0_MH.invokeExact(fd, address, len);
        } catch (Throwable e) {
            throw new IOException(e);
        }
    }

    @Override
    public int write(FileDescriptor fd, long address, int len) throws IOException {
        try {
            if (WRITE0_MH != null) {
                int wlen = (int)WRITE0_MH.invokeExact(fd, address, len);
                if (wlen >= len) {
                   return wlen;
                }
                int remain = len - wlen;
                address += wlen;
                while (remain > 0) {
                    int tmpLen = (int)WRITE0_MH.invokeExact(fd, address, remain);
                    remain  -= tmpLen;
                    address += tmpLen;
                    wlen    += tmpLen;
                }
                return wlen;
            } else {
                int wlen = (int)WRITE0_MH2.invokeExact(fd, address, len);
                if (wlen >= len) {
                    return wlen;
                }
                int remain = len - wlen;
                address += wlen;
                while (remain > 0) {
                    int tmpLen = (int)WRITE0_MH2.invokeExact(fd, address, remain);
                    remain  -= tmpLen;
                    address += tmpLen;
                    wlen    += tmpLen;
                }
                return wlen;
            }
        } catch (Throwable e) {
            throw new IOException(e);
        }
    }

    private static Method getMethod(Class clazz, String name, Class... args) {
        return getMethod0(clazz, name, args, true);
    }

    private static Method getMethod0(Class clazz, String name, Class[] args, boolean first) {
        try {
            Method method = clazz.getDeclaredMethod(name, args);
            if (!Modifier.isPublic(method.getModifiers()) ||
                    !Modifier.isPublic(method.getDeclaringClass().getModifiers()))
                setAccessible(method);
            return method;

        } catch (NoSuchMethodException e) {
            Class superclass = clazz.getSuperclass();
            if (superclass != null)
                try {
                    Method m = getMethod0(superclass, name, args, false);
                    if (m != null)
                        return m;
                } catch (Exception ignored) {
                }
            if (first)
                throw new AssertionError(e);
            return null;
        }
    }

    static void setAccessible(AccessibleObject h) {
        h.setAccessible(true);
    }

    static <V> V getValue(Object obj, String name) throws NoSuchFieldException {
        Class<?> aClass = obj.getClass();
        for (String n : name.split("/")) {
            Field f = getField(aClass, n);
            setAccessible(f);
            try {
                obj = f.get(obj);
                if (obj == null) {
                    return null;
                }
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
            aClass = obj.getClass();
        }
        return (V) obj;
    }
}
