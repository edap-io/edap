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
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    private UnsafeUtil() {}

    /**
     * 获取DirectByteBuffer的内存开始地址
     * @param buffer ByteBuffer对象
     * @return
     */
    public static long address(ByteBuffer buffer) {
        return UNSAFE.getLong(buffer, BUFFER_ADDRESS_OFFSET);
    }


    public static long fieldOffset(Field field) {
        return field == null ? -1 : UNSAFE.objectFieldOffset(field);
    }

    public static void copyMemory(long fromAddress, byte [] dst, int toOffset, int length) {
        copyMemory0(null, fromAddress, dst, UNSAFE.ARRAY_BYTE_BASE_OFFSET + toOffset, length);
    }

    public static void copyMemory(byte[] bs, int offset, long address, int length) {
        copyMemory0(bs, UNSAFE.ARRAY_BYTE_BASE_OFFSET + offset, null, address, length);
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

    public static int readByte(long address) {
        return UNSAFE.getByte(address);
    }
}