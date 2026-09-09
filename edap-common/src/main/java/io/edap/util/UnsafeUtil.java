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

package io.edap.util;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;

import static sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET;
import static sun.misc.Unsafe.ARRAY_CHAR_BASE_OFFSET;

/**
 * Unsafe操作的常用函数
 */
public class UnsafeUtil {

    public static final Unsafe UNSAFE;
    static final long BUFFER_ADDRESS_OFFSET;
    static final long UNSAFE_COPY_THRESHOLD = 1024L * 1024L;

    static {
        try {
            UNSAFE = AccessController.doPrivileged(
                    (PrivilegedExceptionAction<Unsafe>) () -> {
                        Class<Unsafe> k = Unsafe.class;

                        for (Field f : k.getDeclaredFields()) {
                            f.setAccessible(true);
                            Object x = f.get(null);
                            if (k.isInstance(x)) {
                                return k.cast(x);
                            }
                        }
                        // The sun.misc.Unsafe field does not exist.
                        return null;
                    });
            BUFFER_ADDRESS_OFFSET = fieldOffset(field(Buffer.class, "address"));
            if (BUFFER_ADDRESS_OFFSET < 0) {
                // fail-fast:反射拿不到 offset 后,UNSAFE.getLong(buf, -1) 会读 Buffer 对象 header 的某个
                // 字段当 address,返回看似合理但完全错的值(实测 256)。后续 native write 会把数据写到
                // 错位置 → SIGSEGV 或数据错位(JDK 17 + mac aarch64 上是 ~15KB body 限制的真凶)。
                // 这里硬抛,让上游显式处理。
                throw new AssertionError(
                        "UnsafeUtil: BUFFER_ADDRESS_OFFSET unresolved; "
                        + "ensure JVM is started with --add-opens java.base/java.nio=ALL-UNNAMED "
                        + "(direct reflection on java.nio.Buffer.address was blocked by the module system)");
            }
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    private UnsafeUtil() {}

    /**
     * 获取DirectByteBuffer的内存开始地址。
     *
     * <p>优先走 {@code sun.nio.ch.DirectBuffer#address()} —— 这是 JDK 暴露给 NIO 用户的官方 API,
     * 不走反射,不受 module 系统 gating 影响(JDK 9+ 反射 {@code java.nio.Buffer.address} 需要
     * {@code --add-opens java.base/java.nio=ALL-UNNAMED},否则 {@link #BUFFER_ADDRESS_OFFSET}
     * 会静默变成 -1,所有 native 写出都写到错位置)。
     *
     * <p>DirectBuffer 路径需要 {@code --add-exports java.base/sun.nio.ch=ALL-UNNAMED},否则
     * ClassNotFoundException;这时 fallback 到反射路径(已经在 static init 验证过 offset ≥ 0)。
     */
    private static final Class<?> DIRECT_BUFFER_CLASS;
    private static final java.lang.reflect.Method DIRECT_BUFFER_ADDRESS_METHOD;

    static {
        Class<?> dc = null;
        java.lang.reflect.Method m = null;
        try {
            dc = Class.forName("sun.nio.ch.DirectBuffer");
            m = dc.getMethod("address");
        } catch (Throwable ignored) {
            // 模块未导出 sun.nio.ch → fallback 到反射路径
        }
        DIRECT_BUFFER_CLASS = dc;
        DIRECT_BUFFER_ADDRESS_METHOD = m;
    }

    /**
     * 获取DirectByteBuffer的内存开始地址
     * @param buffer ByteBuffer对象(必须是 direct ByteBuffer)
     * @return native memory 起始地址
     * @throws IllegalArgumentException 如果 buffer 不是 direct ByteBuffer
     */
    public static long address(ByteBuffer buffer) {
        if (DIRECT_BUFFER_ADDRESS_METHOD != null && DIRECT_BUFFER_CLASS.isInstance(buffer)) {
            try {
                return (long) DIRECT_BUFFER_ADDRESS_METHOD.invoke(buffer);
            } catch (java.lang.reflect.InvocationTargetException | IllegalAccessException e) {
                throw new IllegalStateException("DirectBuffer.address() invocation failed", e);
            }
        }
        // 反射路径(static init 已保证 BUFFER_ADDRESS_OFFSET ≥ 0)
        return UNSAFE.getLong(buffer, BUFFER_ADDRESS_OFFSET);
    }


    public static long fieldOffset(Field field) {
        return field == null ? -1 : UNSAFE.objectFieldOffset(field);
    }

    public static void copyMemory(long fromAddress, byte [] dst, int toOffset, int length) {
        copyMemory0(null, fromAddress, dst, UNSAFE.ARRAY_BYTE_BASE_OFFSET + toOffset, length);
    }

    public static void copyMemory(Object src, long srcOffset, byte [] dst, int toOffset, int length) {
        copyMemory0(src, srcOffset, dst, UNSAFE.ARRAY_BYTE_BASE_OFFSET + toOffset, length);
    }

    public static void copyMemory(byte[] bs, int offset, long address, int length) {
        copyMemory0(bs, UNSAFE.ARRAY_BYTE_BASE_OFFSET + offset, null, address, length);
    }

    /**
     * 纯地址到地址的内存拷贝。两端都是绝对地址，可用于 direct buffer ↔ 任意内存块。
     */
    public static void copyMemory(long fromAddress, long toAddress, long length) {
        copyMemory0(null, fromAddress, null, toAddress, length);
    }

    public static void copyUtf16le(char[] cs, int offset, byte[] dest, int destOffset, int len) {
        copyMemory0(cs, ARRAY_CHAR_BASE_OFFSET + offset * 2, dest, ARRAY_BYTE_BASE_OFFSET + destOffset, len * 2);
    }

    static void copyMemory0(Object from, long fromOffset, Object to, long toOffset, long length) {
        // use a loop to ensure there is a safe point every so often.
        while (length > 0) {
            long size;
            if (length > UNSAFE_COPY_THRESHOLD) {
                size = UNSAFE_COPY_THRESHOLD;
            } else {
                size = length;
            }
            UNSAFE.copyMemory(from, fromOffset, to, toOffset, size);
            length -= size;
            fromOffset += size;
            toOffset += size;
        }
    }

    public static void copyMemory(byte [] src, int offset, byte [] dst, int toOffset, int length) {
        copyMemory0(src, UNSAFE.ARRAY_BYTE_BASE_OFFSET + offset, dst, UNSAFE.ARRAY_BYTE_BASE_OFFSET + toOffset, length);
    }

    public static Field field(Class<?> clazz, String fieldName) {
        Field field;
        try {
            field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
        } catch (Throwable t) {
            // Failed to access the fields.
            field = null;
        }
        return field;
    }

    public static void writeByte(long address, byte b) {
        UNSAFE.putByte(address, b);
    }

    public static Object getValue(Object value, long offset) {
        return UNSAFE.getObject(value, offset);
    }

    public static byte getByte(Object value, long offset) {
        return UNSAFE.getByte(value, offset);
    }

    public static byte readByte(long address) {
        return UNSAFE.getByte(address);
    }

    public static int getInt(long address) {
        return UNSAFE.getInt(address);
    }

    public static long getLong(long address) {
        return UNSAFE.getLong(address);
    }

    public static void putByte(Object obj, long offset, byte b) {
        UNSAFE.putByte(obj, offset, b);
    }

    public static void putObject(Object obj, long offset, Object value) {
        UNSAFE.putObject(obj, offset, value);
    }

    public static Object allocateInstance(Class<?> clazz) throws InstantiationException {
        return UNSAFE.allocateInstance(clazz);
    }

    public static void writeByte(byte[] bs, int offset, byte b) {
        UNSAFE.putByte(bs, (long)(UNSAFE.ARRAY_BYTE_BASE_OFFSET + offset), b);
    }

    public static void getAndSetObject(Object obj, long offset, Object newValue) {
        UNSAFE.getAndSetObject(obj, offset, newValue);
    }
}