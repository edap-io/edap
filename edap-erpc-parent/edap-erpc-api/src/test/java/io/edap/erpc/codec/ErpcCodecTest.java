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

package io.edap.erpc.codec;

import io.edap.erpc.RpcException;
import io.edap.erpc.codec.fastprotobuf.FastProtobufCodec;
import io.edap.erpc.codec.json.JsonErpcCodec;
import io.edap.erpc.codec.protobuf.ProtobufErpcCodec;
import io.edap.json.Eson;
import io.edap.protobuf.ProtoBuf;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 编解码器 SPI 的往返测试 —— 每个内置实现都要能把同一个 DTO 编解码回来。
 *
 * <p>★ 关键不变量：<b>编码器交出的字节必须与「直接用底层框架编码」的字节逐字节相同</b>
 * —— 换编码入口（{@code ProtoBuf.THREAD_WRITER} / {@code Eson.THREAD_WRITER}
 * 交视图 vs {@code toByteArray()}）不能改变线上格式。</p>
 */
public class ErpcCodecTest {

    /** 测试用的业务 DTO（不带协议注解）。 */
    public static class Dto {
        private String name;
        private int    value;

        public Dto() {
        }

        public Dto(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }
    }

    /**
     * 把编码器交出的「借来的」载荷拷出来 —— 借来的缓冲下一次编码就会被覆盖。
     */
    private static byte[] bytesOf(ErpcCodec codec, Object obj) {
        ErpcWriter writer = codec.encode(obj);
        assertNotNull(writer, "非 null 对象必须交出载荷载体");
        return Arrays.copyOf(writer.buffer(), writer.size());
    }

    @Test
    public void protobufRoundTripsAPojo() {
        ProtobufErpcCodec s = new ProtobufErpcCodec();
        byte[] bytes = bytesOf(s, new Dto("hello", 21));
        assertNotNull(bytes);
        Dto back = s.decode(bytes, 0, bytes.length, Dto.class);
        assertEquals("hello", back.getName());
        assertEquals(21, back.getValue());
    }

    @Test
    public void jsonRoundTripsAPojo() {
        JsonErpcCodec s = new JsonErpcCodec();
        byte[] bytes = bytesOf(s, new Dto("hello", 21));
        assertNotNull(bytes);
        Dto back = s.decode(bytes, 0, bytes.length, Dto.class);
        assertEquals("hello", back.getName());
        assertEquals(21, back.getValue());
    }

    @Test
    public void fastProtobufRoundTripsAPojo() {
        FastProtobufCodec s = new FastProtobufCodec();
        byte[] bytes = bytesOf(s, new Dto("hello", 21));
        assertNotNull(bytes);
        Dto back = s.decode(bytes, 0, bytes.length, Dto.class);
        assertEquals("hello", back.getName());
        assertEquals(21, back.getValue());
    }

    @Test
    public void nullInNullOut() {
        ProtobufErpcCodec s = new ProtobufErpcCodec();
        assertNull(s.encode(null));
        assertNull(s.decode(null, 0, 0, Dto.class));
    }

    /**
     * ★ protobuf 的字节必须与 {@link ProtoBuf#toByteArray(Object)} 完全一致
     * —— 换编码入口不能改变线上载荷格式。
     */
    @Test
    public void protobufBytesMatchProtoBufToByteArray() {
        ProtobufErpcCodec s = new ProtobufErpcCodec();
        byte[] actual = bytesOf(s, new Dto("hello", 21));
        byte[] expected = ProtoBuf.toByteArray(new Dto("hello", 21));
        assertArrayEquals(expected, actual, "protobuf 载荷字节必须与 ProtoBuf.toByteArray 逐字节相同");
    }

    /**
     * ★ json 的字节必须与 {@link Eson#toJsonBytes(Object)} 完全一致
     * —— 换编码入口（{@code Eson.THREAD_WRITER} 交视图 vs {@code toJsonBytes}）
     * 不能改变线上载荷格式。
     */
    @Test
    public void jsonBytesMatchEsonToJsonBytes() {
        JsonErpcCodec s = new JsonErpcCodec();
        byte[] actual = bytesOf(s, new Dto("hello", 21));
        byte[] expected = Eson.toJsonBytes(new Dto("hello", 21));
        assertArrayEquals(expected, actual, "json 载荷字节必须与 Eson.toJsonBytes 逐字节相同");
    }

    /**
     * ERROR 帧的载荷就是 {@link RpcException} —— 它必须能被两种编解码器编解码。
     */
    @Test
    public void rpcExceptionRoundTripsWithProtobuf() {
        RpcException err = new RpcException();
        err.setStatus(13);
        err.setClazzName("java.lang.IllegalStateException");
        err.setMessage("boom");

        ProtobufErpcCodec s = new ProtobufErpcCodec();
        byte[] bytes = bytesOf(s, err);
        RpcException back = s.decode(bytes, 0, bytes.length, RpcException.class);
        assertEquals(13, back.getStatus());
        assertEquals("java.lang.IllegalStateException", back.getClazzName());
        assertEquals("boom", back.getMessage());
    }

    @Test
    public void rpcExceptionRoundTripsWithJson() {
        RpcException err = new RpcException();
        err.setStatus(17);
        err.setClazzName("io.edap.erpc.ErpcStatus");
        err.setMessage("未知的方法号");

        JsonErpcCodec s = new JsonErpcCodec();
        byte[] bytes = bytesOf(s, err);
        RpcException back = s.decode(bytes, 0, bytes.length, RpcException.class);
        assertEquals(17, back.getStatus());
        assertEquals("io.edap.erpc.ErpcStatus", back.getClazzName());
        assertEquals("未知的方法号", back.getMessage());
    }

    /**
     * 注册表：id 与名字两套索引都要能查到内置实现。
     */
    @Test
    public void registryFindsBuiltinsByIdAndName() {
        ErpcCodecRegistry registry = ErpcCodecRegistry.instance();
        assertEquals(ErpcCodecIds.PROTOBUF, registry.defaultCodec().id());
        assertNotNull(registry.byId(ErpcCodecIds.PROTOBUF));
        assertNotNull(registry.byId(ErpcCodecIds.JSON));
        assertNotNull(registry.byName("protobuf"));
        assertNotNull(registry.byName("JSON"));
        assertNull(registry.byId(999));
        assertNull(registry.byName("不存在"));
    }
}
