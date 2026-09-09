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
import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static io.edap.util.ClazzUtil.getField;

public class MethodHandleNetIO implements EdapNetIO {

    private static final Logger LOG = LoggerManager.getLogger(MethodHandleNetIO.class);

    // sun.nio.ch.IOStatus 的负值约定,从 JDK 7 起稳定:
    //   -1 EOF          fd 已关闭 / write 收到 EOF,真实错误
    //   -2 UNAVAILABLE  EAGAIN / EWOULDBLOCK,transient,本轮没写出,应返回 0
    //   -3 INTERRUPTED  系统调用被信号中断(EINTR),本调用应立刻重试
    //   -4 THROWN       errno 已设,需抛 IOException
    private static final int IO_EOF = -1;
    private static final int IO_UNAVAILABLE = -2;
    private static final int IO_INTERRUPTED = -3;
    private static final int IO_THROWN = -4;

    protected static final MethodHandle READ0_MH;
    protected static final MethodHandle WRITE0_MH;
    protected static final MethodHandle WRITE0_MH2;

    static {
        Class<?> fdi;
        try {
            fdi = Class.forName("sun.nio.ch.FileDispatcherImpl");


            MethodHandles.Lookup lookup = MethodHandles.lookup();

            lookup.in(fdi);
            Method read0 = getMethod(fdi, "read0", new Class[]{FileDescriptor.class, long.class, int.class});
            READ0_MH = lookup.unreflect(read0);

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
            WRITE0_MH2 = write0Mh2;
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
                int wlen = invokeWrite0(WRITE0_MH, fd, address, len);
                if (wlen == IO_UNAVAILABLE) {
                    LOG.debug("write0 returned UNAVAILABLE (EAGAIN/EWOULDBLOCK), transient — return 0, upper layer retries via OP_WRITE");
                    return 0;
                }
                if (wlen < 0) {
                    // EOF (-1) / THROWN (-4) / 其它负值 → 真实 OS 错,继续抛 IOException。
                    // 这一支不能吞:fd 被关闭、broken pipe、EBADF 等都会落到这里,
                    // 必须让上层 NIO session 感知并关连接,不能 OP_WRITE 空转。
                    throw new IOException("write0 failed, return value=" + wlen);
                }
                if (wlen >= len) {
                   return wlen;
                }
                int remain = len - wlen;
                address += wlen;
                while (remain > 0) {
                    int tmpLen = invokeWrite0(WRITE0_MH, fd, address, remain);
                    if (tmpLen == IO_UNAVAILABLE) {
                        LOG.debug("write0 returned UNAVAILABLE mid-write, returning partial " + wlen);
                        return wlen;
                    }
                    if (tmpLen < 0) {
                        throw new IOException("write0 failed mid-write, return value=" + tmpLen);
                    }
                    if (tmpLen == 0) {
                        // 极少数驱动 0 字节表示 EAGAIN 的另一种表示,交给上层处理。
                        return wlen;
                    }
                    remain  -= tmpLen;
                    address += tmpLen;
                    wlen    += tmpLen;
                }
                return wlen;
            } else {
                int wlen = invokeWrite0(WRITE0_MH2, fd, address, len);
                if (wlen == IO_UNAVAILABLE) {
                    LOG.debug("write0 (4-arg) returned UNAVAILABLE, transient — return 0");
                    return 0;
                }
                if (wlen < 0) {
                    throw new IOException("write0 (4-arg) failed, return value=" + wlen);
                }
                if (wlen >= len) {
                    return wlen;
                }
                int remain = len - wlen;
                address += wlen;
                while (remain > 0) {
                    int tmpLen = invokeWrite0(WRITE0_MH2, fd, address, remain);
                    if (tmpLen == IO_UNAVAILABLE) {
                        return wlen;
                    }
                    if (tmpLen < 0) {
                        throw new IOException("write0 (4-arg) failed mid-write, return value=" + tmpLen);
                    }
                    if (tmpLen == 0) {
                        return wlen;
                    }
                    remain  -= tmpLen;
                    address += tmpLen;
                    wlen    += tmpLen;
                }
                return wlen;
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            // invokeExact 自身抛 WrongMethodTypeException / NullPointerException /
            // InternalError / OutOfMemoryError 等。fd 为 null 时会 NullPointerException,
            // 这也属于"无法继续写"的状态,必须让上层走关连接分支,不能 retry。
            throw new IOException(e);
        }
    }

    /**
     * 调用 write0 syscall 并处理 IO_INTERRUPTED(-3, EINTR) 重试。
     * 返回 IOStatus 原始值,交给调用方按 UNAVAILABLE / EOF / THROWN 分支处理。
     * invokeExact 抛出的任意 Throwable 会被包成 IOException 直接传出,不参与重试。
     */
    private static int invokeWrite0(MethodHandle mh, FileDescriptor fd, long address, int len) throws IOException {
        while (true) {
            int n;
            try {
                n = (int) mh.invokeExact(fd, address, len);
            } catch (IOException e) {
                throw e;
            } catch (Throwable e) {
                throw new IOException(e);
            }
            if (n != IO_INTERRUPTED) {
                return n;
            }
            // INTERRUPTED:write syscall 被信号打断,EINTR,立刻重试同一调用。
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
