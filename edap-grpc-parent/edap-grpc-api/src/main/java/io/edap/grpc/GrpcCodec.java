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

package io.edap.grpc;

import io.edap.io.BufOut;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.ProtoFieldInfo;
import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.Field;
import io.edap.util.AsmUtil;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gRPC 消息的 protobuf 编解码。
 *
 * <p>只使用 edap 自研的 protobuf 组件（{@code edap-protobuf}），不依赖 google 的
 * protobuf-java。编解码走<b>标准</b>编解码器（{@code CodecType.STANDARD}），
 * 因此 wire type 与 google protobuf 完全一致。
 *
 * <p><b>私有 wire type 的拒绝</b>：edap 在标准 protobuf 之外有两个私有扩展
 * —— {@code WireType.OBJECT(6)}（任意类型，Java 侧为 {@code Object} 字段）和
 * {@code WireType.CHAR_LENGHT_STRING(7)}（按字符长度编码的字符串，仅 FAST 编解码器使用）。
 * google 的 protobuf 解析器不认识这两个 wire type，一旦泄漏到 gRPC 的字节流上，
 * 客户端会直接报解析错误。因此 gRPC 路径上：
 * <ul>
 *   <li>编码器固定使用 STANDARD 编解码器，从根上杜绝 wire type 7；</li>
 *   <li>消息类型中出现 {@code Field.Type.OBJECT} 的字段时 fail-fast，
 *       在第一次编解码前就抛出 {@link GrpcException}。</li>
 * </ul>
 */
public final class GrpcCodec {

    /**
     * 已经校验过类型安全性的消息类型。校验失败的类型不会缓存，下次仍然会报错。
     */
    private static final ConcurrentHashMap<Class<?>, Boolean> VALIDATED = new ConcurrentHashMap<>();

    private GrpcCodec() {
    }

    /**
     * 把消息对象编码成 protobuf 字节。
     *
     * @param type    消息类型，可以为 null（此时用消息对象自身的类型）
     * @param message 消息对象
     * @return protobuf 字节
     */
    public static byte[] encode(Class<?> type, Object message) {
        if (message == null) {
            throw new GrpcException("gRPC 消息不能为 null: " + typeName(type));
        }
        validate(type == null ? message.getClass() : type);
        ProtoBufWriter writer = ProtoBuf.THREAD_WRITER.get();
        writer.reset();
        BufOut out = writer.getBufOut();
        out.reset();
        try {
            ProtoBufEncoder encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(message.getClass());
            encoder.encode(writer, message);
            return writer.toByteArray();
        } catch (EncodeException e) {
            throw new GrpcException("protobuf 编码失败: " + message.getClass().getName(), e);
        }
    }

    /**
     * 把 protobuf 字节解码成消息对象。
     *
     * @param type   消息类型
     * @param data   protobuf 字节
     * @param offset 数据的起始位置
     * @param length 数据的长度
     * @param <T>    消息类型
     * @return 消息对象
     */
    public static <T> T decode(Class<T> type, byte[] data, int offset, int length) {
        if (type == null) {
            throw new GrpcException("gRPC 消息类型不能为 null");
        }
        validate(type);
        try {
            return ProtoBuf.toObject(data, offset, length, type);
        } catch (ProtoException e) {
            throw new GrpcException("protobuf 解码失败: " + type.getName(), e);
        }
    }

    /**
     * 检查消息类型是否可以用于 gRPC。
     *
     * <p>检查通过的类型会被缓存，同一个类型只检查一次。
     *
     * @param type 消息类型
     */
    public static void validate(Class<?> type) {
        if (type == null) {
            return;
        }
        VALIDATED.computeIfAbsent(type, t -> {
            checkType(t, new HashSet<>());
            return Boolean.TRUE;
        });
    }

    private static void checkType(Class<?> type, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) {
            return;
        }
        List<ProtoFieldInfo> fields;
        try {
            fields = ProtoUtil.getProtoFields(type);
        } catch (IOException e) {
            throw new GrpcException("读取 protobuf 字段失败: " + type.getName(), e);
        }
        if (fields == null) {
            return;
        }
        for (ProtoFieldInfo pfi : fields) {
            ProtoField protoField = pfi.protoField;
            if (protoField == null) {
                continue;
            }
            if (protoField.type() == Field.Type.OBJECT) {
                throw new GrpcException("gRPC 不支持 edap 私有的 wire type 6/7，"
                        + type.getName() + " 的字段 " + pfi.field.getName() + " 是 OBJECT 类型");
            }
            Type genericType = pfi.field.getGenericType();
            if (protoField.type() == Field.Type.MAP && mapHasObject(genericType)) {
                throw new GrpcException("gRPC 不支持 edap 私有的 wire type 6/7，"
                        + type.getName() + " 的字段 " + pfi.field.getName() + " 的 Map 元素是 OBJECT 类型");
            }
            for (Class<?> nested : nestedTypes(genericType)) {
                checkType(nested, visited);
            }
        }
    }

    /**
     * 收集字段类型中嵌套的消息类型。
     *
     * @param genericType 字段的泛型类型
     * @return 嵌套的消息类型
     */
    private static List<Class<?>> nestedTypes(Type genericType) {
        List<Class<?>> result = new ArrayList<>();
        collectPojoTypes(genericType, result);
        return result;
    }

    private static void collectPojoTypes(Type type, List<Class<?>> out) {
        if (type instanceof Class) {
            Class<?> cls = (Class<?>) type;
            if (cls.isArray()) {
                Class<?> item = cls.getComponentType();
                if (!item.isPrimitive() && AsmUtil.isPojo(item)) {
                    out.add(item);
                }
            } else if (AsmUtil.isPojo(cls)) {
                out.add(cls);
            }
        } else if (type instanceof ParameterizedType) {
            for (Type item : ProtoUtil.getAllPojoTypes(type)) {
                if (item instanceof Class) {
                    collectPojoTypes(item, out);
                }
            }
        }
    }

    /**
     * 判断 Map 字段的 key 或 value 是否是 {@code Object} 类型（会被编码成私有 wire type 6）。
     *
     * @param genericType 字段的泛型类型
     * @return true 表示存在 Object 类型的元素
     */
    private static boolean mapHasObject(Type genericType) {
        if (!(genericType instanceof ParameterizedType)) {
            return false;
        }
        Type[] args = ((ParameterizedType) genericType).getActualTypeArguments();
        if (args == null) {
            return false;
        }
        for (Type arg : args) {
            if (arg == Object.class) {
                return true;
            }
        }
        return false;
    }

    private static String typeName(Class<?> type) {
        return type == null ? "null" : type.getName();
    }
}
