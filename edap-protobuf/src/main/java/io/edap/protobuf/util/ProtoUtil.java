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

package io.edap.protobuf.util;

import io.edap.protobuf.ProtoBuf.ProtoFieldInfo;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoPersister;
import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.builder.ProtoV2Builder;
import io.edap.protobuf.builder.ProtoV3Builder;
import io.edap.protobuf.model.MessageInfo;
import io.edap.protobuf.model.ProtoTypeInfo;
import io.edap.protobuf.wire.*;
import io.edap.protobuf.wire.Field.Cardinality;
import io.edap.protobuf.wire.Field.Type;
import io.edap.protobuf.wire.exceptions.ProtoParseException;
import io.edap.protobuf.wire.parser.ProtoParser;
import io.edap.util.AsmUtil;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.*;
import static io.edap.util.CryptUtil.md5;
import static java.lang.reflect.Modifier.isPublic;

public class ProtoUtil {

    private ProtoUtil() {}

    public static boolean implInterface(Class type, Class iface) {
        Class<?>[] ifaces = type.getInterfaces();
        for (Class<?> cls : ifaces) {
            if (cls.getName() == iface.getName()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPacked(ProtoFieldInfo pfi) {
        return pfi.protoField.type().packable();
    }

    /**
     * 判断一个Class的是否是Map的子类，如果是返回Map的类型信息，否则返回null
     * @param clazz
     * @return
     */
    public static java.lang.reflect.Type parentMapType(Class clazz) {
        if (isMap(clazz)) {
            return clazz;
        }
        Class pclazz = clazz.getSuperclass();
        Class cclazz = clazz;
        while (pclazz != null) {
            if (isMap(pclazz)) {
                return cclazz.getGenericSuperclass();
            }
            cclazz = pclazz;
            pclazz = pclazz.getSuperclass();

        }
        return null;
    }

    public static List<ProtoFieldInfo> getProtoFields(Class pojoClass) throws IOException {
        List<ProtoFieldInfo> profields = new ArrayList<>();
        List<Field> allfields = getClassFields(pojoClass);
        List<Method> methods = getClassMethods(pojoClass);
        Map<String, Method> aMethod = new HashMap<>();
        for (Method m : methods) {
            aMethod.put(m.getName(), m);
        }
        List<Field> fields = new ArrayList<>(allfields.size());
        for (Field f : allfields) {
            if (!f.getDeclaringClass().getName().startsWith("java.")) {
                fields.add(f);
            }
        }

        boolean hasProtoAnn = false;
        for (Field f : fields) {
            if (needEncode(f)) {
                ProtoFieldInfo pfi = new ProtoFieldInfo();
                pfi.field = f;
                Method em = null;
                try {
                    em = getAccessMethod(f, aMethod);
                    if (isIgnore(f, em)) {
                        continue;
                    }
                } catch (Throwable t) {
                    new RuntimeException(pojoClass.getName() + " getAccessMethod error", t);
                }
                pfi.getMethod = em;
                try {
                    Method setMethod = getSetMethod(f, aMethod);
                    if (setMethod != null) {
                        pfi.setMethod = setMethod;
                    }
                } catch (Throwable t) {
                    new RuntimeException(pojoClass.getName() + " getSetMethod error", t);
                }
                pfi.hasGetAccessed = Modifier.isPublic(f.getModifiers()) || pfi.getMethod != null;
                pfi.hasSetAccessed = Modifier.isPublic(f.getModifiers()) || pfi.setMethod != null;
                ProtoField pf = getProtoAnnotation(f, em);
                if (pf != null) {
                    pfi.protoField = pf;
                    hasProtoAnn = true;
                }
                profields.add(pfi);
            }
        }
        if (!hasProtoAnn) {
            generateProtoField(pojoClass, profields);
        }

        return profields;
    }

    private static boolean isIgnore(Field f, Method getMethod) {
        Annotation[] anns = f.getDeclaredAnnotations();
        if (isIgnore(anns)) {
            return true;
        }
        if (getMethod == null) {
            return false;
        }
        anns = getMethod.getDeclaredAnnotations();
        if (isIgnore(anns)) {
            return true;
        }
        return false;
    }

    private static boolean isIgnore(Annotation[] anns) {
        if (anns == null) {
            return false;
        }
        Object value;
        for (Annotation ann : anns) {
            Map<String, Object> valueMap = getAnnotationValueMap(ann);
            switch (ann.annotationType().getName()) {
                case "com.fasterxml.jackson.annotation.JsonIgnore":
                    value = valueMap.get("value");
                    if (value != null && value instanceof Boolean) {
                        return ((Boolean)value).booleanValue();
                    }
                    break;
                case "com.alibaba.fastjson.annotation.JSONField":
                    value = valueMap.get("serialize");
                    if (value != null && value instanceof Boolean) {
                        return !((Boolean)value).booleanValue();
                    }
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    private static Map<String, Object> getAnnotationValueMap(Annotation ann) {
        if (ann == null) {
            return new HashMap<>();
        }
        try {
            InvocationHandler invocationhdl = Proxy.getInvocationHandler(ann);
            Field memField = invocationhdl.getClass().getDeclaredField("memberValues");
            memField.setAccessible(true);
            return (Map) memField.get(invocationhdl);
        } catch (Throwable t) {

        }
        return new HashMap<>();
    }

    public static byte[] buildFieldData(int tag, io.edap.protobuf.wire.Field.Type type, io.edap.protobuf.wire.Field.Cardinality cardinality) {
        return buildFieldData(tag, type, cardinality, Syntax.PROTO_3, null);
    }

    public static byte[] buildbuildFieldDataFieldData(int tag, Type type, Cardinality cardinality, Syntax syntax) {
        return buildFieldData(tag, type, cardinality, syntax, null);
    }

    public static byte[] buildFieldData(int tag, Type type, Cardinality cardinality, Syntax syntax, String... options) {
        return varIntEncode(buildFieldValue(tag, type, cardinality, syntax, options));
    }

    public static int buildFieldValue(int tag, Type type, Cardinality cardinality) {
        return buildFieldValue(tag, type, cardinality, Syntax.PROTO_3);
    }

    public static int buildFieldValue(int tag, Type type, Cardinality cardinality, Syntax syntax) {
        return buildFieldValue(tag, type, cardinality, syntax, null);
    }

    public static int buildFieldValue(int tag, Type type, Cardinality cardinality, Syntax syntax, String... options) {
        WireType wireType;
        switch (type) {
            case INT32:
            case INT64:
            case UINT32:
            case UINT64:
            case SINT32:
            case SINT64:
            case BOOL:
            case ENUM:
                if (syntax == Syntax.PROTO_3 && cardinality == Cardinality.REPEATED) {
                    wireType = WireType.LENGTH_DELIMITED;
                } else {
                    wireType = WireType.VARINT;
                }
                break;
            case FIXED64:
            case SFIXED64:
            case DOUBLE:
                if (syntax == Syntax.PROTO_3 && cardinality == Cardinality.REPEATED) {
                    wireType = WireType.LENGTH_DELIMITED;
                } else {
                    wireType = WireType.FIXED64;
                }
                break;
            case FIXED32:
            case SFIXED32:
            case FLOAT:
                if (syntax == Syntax.PROTO_3 && cardinality == Cardinality.REPEATED) {
                    wireType = WireType.LENGTH_DELIMITED;
                } else {
                    wireType = WireType.FIXED32;
                }
                break;
            case GROUP:
                wireType = WireType.START_GROUP;
                break;
            case OBJECT:
                wireType = WireType.OBJECT;
                break;
            default:
                wireType = WireType.LENGTH_DELIMITED;
        }
        return WireFormat.makeTag(tag, wireType);
    }

    public static byte[] varIntEncode(int value) {
        int size = computeRawVarint32Size(value);
        byte[] bs = new byte[size];
        int start = 0;
        if ((value & ~0x7F) == 0) {
            bs[start++] = (byte) value;
        } else {
            bs[start++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                bs[start++] = (byte) value;
            } else {
                bs[start++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    bs[start++] = (byte) value;
                } else {
                    bs[start++] = (byte) ((value & 0x7F) | 0x80);
                    value >>>= 7;
                    if ((value & ~0x7F) == 0) {
                        bs[start++] = (byte) value;
                    } else {
                        bs[start++] = (byte) ((value & 0x7F) | 0x80);
                        value >>>= 7;
                        if ((value & ~0x7F) == 0) {
                            bs[start++] = (byte) value;
                        }
                    }
                }
            }
        }
        return bs;
    }

    private static void generateProtoField(Class pojoClass, List<ProtoFieldInfo> profields) throws IOException {
        // 如果有proto文件持久化器，则查询是否有该class的持久化proto文件，如果有则进行相应的兼容处理
        Proto proto = null;
        ProtoPersister protoPersister = ProtoBufCodecRegister.INSTANCE.getProtoPersister();
        if (protoPersister != null) {
            String protoData = protoPersister.getProto(pojoClass.getName());
            if (!StringUtil.isEmpty(protoData)) {
                try {
                    proto = new ProtoParser(protoData).parse();
                } catch (ProtoParseException e) {

                }
            }
        }

        Map<String, io.edap.protobuf.wire.Field> protoFields = new HashMap<>();
        if (proto != null) {
            List<Message> msgs = proto.getMessages();
            Message msg = null;
            if (CollectionUtils.isEmpty(msgs)) {
                for (Message m : msgs) {
                    if (m.getName().equals(pojoClass.getSimpleName())) {
                        msg = m;
                        break;
                    }
                }
            }
            if (msg != null && !CollectionUtils.isEmpty(msg.getFields())) {
                msg.getFields().forEach(f -> protoFields.put(f.getName(), f));
            }
        }
        List<String> fieldNames = new ArrayList<>();
        profields.forEach(e -> fieldNames.add(e.field.getName()));
        int maxTag = 0;
        if (!CollectionUtils.isEmpty(protoFields)) {
            for (int i=0;i<profields.size();i++) {
                ProtoFieldInfo pfi = profields.get(i);
                io.edap.protobuf.wire.Field field = protoFields.get(pfi.field.getName());
                if (field != null) {
                    if (maxTag < field.getTag()) {
                        maxTag = field.getTag();
                    }
                    pfi.protoField = buildProtoFieldAnnotation(field);
                }
            }
        }
        maxTag++;
        int old = maxTag;
        for (int i=0;i<profields.size();i++) {
            ProtoFieldInfo pfi = profields.get(i);
            if (pfi.protoField != null) {
                continue;
            }
            ProtoField pf = buildProtoFieldAnnotation(maxTag++, pfi.field);
            pfi.protoField = pf;
        }
        if (protoPersister != null && old != maxTag) {
            Proto nproto = buildProto(pojoClass, profields);
            if (nproto.getSyntax() == Syntax.PROTO_3) {
                ProtoV3Builder builder = new ProtoV3Builder(nproto);
                protoPersister.persist(pojoClass.getName(), builder.toProtoString());
            } else if (nproto.getSyntax() == Syntax.PROTO_2) {
                ProtoV2Builder builder = new ProtoV2Builder(nproto);
                protoPersister.persist(pojoClass.getName(), builder.toProtoString());
            }
        }
    }

    private static ProtoField buildProtoFieldAnnotation(io.edap.protobuf.wire.Field field) {
        ProtoField pf = new ProtoField() {
            @Override
            public Cardinality cardinality() {
                return field.getCardinality();
            }

            @Override
            public int tag() {
                return field.getTag();
            }

            @Override
            public Type type() {
                return Type.valueOf(field.getType());
            }

            @Override
            public String[] options() {
                List<Option> options = field.getOptions();
                if (CollectionUtils.isEmpty(options)) {
                    return null;
                }
                String[] ops = new String[options.size()];
                Option o;
                for (int i=0;i<options.size();i++) {
                    o = options.get(i);
                    ops[i] = o.getName() + "=" + o.getValue();
                }
                return ops;
            }

            @Override
            public String comment() {
                return "";
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return null;
            }
        };
        return pf;
    }

    private static Proto buildProto(Class pojoClass, List<ProtoFieldInfo> profields) throws IOException {
        Proto proto = new Proto();
        proto.setSyntax(Syntax.PROTO_3);
        proto.setProtoPackage(pojoClass.getPackage().getName());

        List<Message> msgs = new ArrayList<>();
        Message msg = new Message();
        msg.setName(pojoClass.getSimpleName());
        if (!CollectionUtils.isEmpty(profields)) {
            for (ProtoFieldInfo pfi : profields) {
                io.edap.protobuf.wire.Field f = new io.edap.protobuf.wire.Field();
                f.setName(pfi.field.getName());
                f.setCardinality(pfi.protoField.cardinality());
                f.setTag(pfi.protoField.tag());
                if (!CollectionUtils.isEmpty(pfi.protoField.options())) {
                    List<Option> options = new ArrayList<>();
                    for (String ovalue : pfi.protoField.options()) {
                        int index = ovalue.indexOf("=");
                        if (index == -1) {
                            Option option = new Option();
                            option.setName(ovalue);
                            option.setValue(ovalue);
                            options.add(option);
                        } else {
                            Option option = new Option();
                            option.setName(ovalue.substring(0, index));
                            option.setValue(ovalue.substring(index + 1));
                            options.add(option);
                        }
                    }
                    f.setOptions(options);
                }
                String type = pfi.protoField.type().value();
                if (pfi.protoField.type() == Type.ENUM) {
                    type = pfi.field.getType().getSimpleName();
                    proto.addImport(toInternalName(pfi.field.getType().getName()) + ".proto");
                    ProtoEnum protoEnum = getEnumProto(pfi.field.getType());
                } else if (pfi.protoField.type() == Type.MESSAGE) {
                    proto.addImport(toInternalName(pfi.field.getType().getName()) + ".proto");
                    type = pfi.field.getType().getSimpleName();
                }
                f.setType(type);
                msg.addField(f);
            }
        }
        msgs.add(msg);
        proto.setMessages(msgs);
        return proto;
    }

    public static ProtoEnum getEnumProto(Class javaEnum) throws IOException {
        ProtoEnum protoEnum = new ProtoEnum();
        protoEnum.setName(javaEnum.getSimpleName());
        Object[] values = javaEnum.getEnumConstants();
        List<ProtoEnum.EnumEntry> entries = new ArrayList<>();
        Method[] eMethods = javaEnum.getDeclaredMethods();
        Method valueMethod = null;
        List<String> valueNames = new ArrayList<>();
        valueNames.add("getNumber");
        valueNames.add("getValue");
        Map<String, Method> vMethods = new HashMap<>();
        for (Method m : eMethods) {
            if (isPublic(m.getModifiers()) && m.getParameterCount() == 0 && "int".equals(m.getReturnType().getName())) {
                vMethods.put(m.getName(), m);
            }
        }
        for (String name : valueNames) {
            if (vMethods.containsKey(name)) {
                valueMethod = vMethods.get(name);
                break;
            }
        }
        if (valueMethod == null && !CollectionUtils.isEmpty(vMethods)) {
            for (Method m : vMethods.values()) {
                valueMethod = m;
            }
        }

        Object[] params = new Object[0];
        for (int i=0;i<values.length;i++) {
            Object v = values[i];
            System.out.println("v=" + v);
            ProtoEnum.EnumEntry entry = new ProtoEnum.EnumEntry();
            entry.setLabel(v.toString());
            int value = i;
            if (valueMethod != null) {
                try {
                    value = (int)valueMethod.invoke(v, params);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
            entry.setValue(value);
            entries.add(entry);
        }
        protoEnum.setEntries(entries);

        ProtoPersister persister = ProtoBufCodecRegister.INSTANCE.getProtoPersister();
        if (persister != null) {
            Proto eproto = new Proto();
            eproto.setSyntax(Syntax.PROTO_3);
            eproto.setProtoPackage(javaEnum.getPackage().getName());
            eproto.addEnum(protoEnum);

            if (eproto.getSyntax() == Syntax.PROTO_3) {
                ProtoV3Builder builder = new ProtoV3Builder(eproto);
                persister.persist(javaEnum.getName(), builder.toProtoString());
            } else if (eproto.getSyntax() == Syntax.PROTO_2) {
                ProtoV2Builder builder = new ProtoV2Builder(eproto);
                persister.persist(javaEnum.getName(), builder.toProtoString());
            }
        }
        return protoEnum;
    }

    private static ProtoField buildProtoFieldAnnotation(final int tag, final Field field) {
        Cardinality cardinality = Cardinality.OPTIONAL;
        if (AsmUtil.isList(field.getGenericType())
                || AsmUtil.isSet(field.getGenericType())
                || isRepeatedArray(field.getGenericType())
                || isIterable(field.getGenericType())) {
            cardinality = Cardinality.REPEATED;
        }
        Type type = javaToProtoType(field.getGenericType()).getProtoType();
        String[] opitons = null;
        return buildProtoFieldAnnotation(tag, cardinality, type, opitons);
    }

    public static ProtoTypeInfo javaToProtoType(Class javaType) {
        ProtoTypeInfo typeInfo = new ProtoTypeInfo();
        if (javaType.isEnum()) {
            typeInfo.setProtoType(Type.ENUM);
            return typeInfo;
        }
        String typeName = javaType.getName();
        MessageInfo msgInfo;
        switch (typeName) {
            case "void":
                msgInfo = new MessageInfo();
                msgInfo.setMessageName("Empty");
                msgInfo.setImpFile("edap-idl/Empty.proto");
                msgInfo.setJavaType(javaType);
                typeInfo.setMessageInfo(msgInfo);
                break;
            case "boolean":
            case "java.lang.Boolean":
                msgInfo = new MessageInfo();
                msgInfo.setMessageName("BoolValue");
                msgInfo.setImpFile("edap-idl/wrappers.proto");
                msgInfo.setJavaType(javaType);
                typeInfo.setMessageInfo(msgInfo);
                typeInfo.setProtoType(Type.BOOL);
                break;
            case "byte":
            case "java.lang.Byte":
            case "char":
            case "java.lang.Character":
            case "short":
            case "java.lang.Short":
            case "int":
            case "java.lang.Integer":
                msgInfo = new MessageInfo();
                msgInfo.setMessageName("Int32Value");
                msgInfo.setImpFile("edap-idl/wrappers.proto");
                msgInfo.setJavaType(javaType);
                typeInfo.setMessageInfo(msgInfo);
                typeInfo.setProtoType(Type.INT32);
                break;
            case "long":
            case "java.lang.Long":
            case "java.util.Date":
            case "java.util.Calendar":
            case "java.time.LocalDateTime":
                msgInfo = new MessageInfo();
                msgInfo.setMessageName("Int64Value");
                msgInfo.setImpFile("edap-idl/wrappers.proto");
                msgInfo.setJavaType(javaType);
                typeInfo.setMessageInfo(msgInfo);
                typeInfo.setProtoType(Type.INT64);
                break;
            case "float":
            case "java.lang.Float":
                msgInfo = new MessageInfo();
                msgInfo.setMessageName("FloatValue");
                msgInfo.setImpFile("edap-idl/wrappers.proto");
                msgInfo.setJavaType(javaType);
                typeInfo.setProtoType(Type.FLOAT);
                break;
            case "double":
            case "java.lang.Double":
                msgInfo = new MessageInfo();
                msgInfo.setMessageName("DoubleValue");
                msgInfo.setImpFile("edap-idl/wrappers.proto");
                msgInfo.setJavaType(javaType);
                typeInfo.setMessageInfo(msgInfo);
                typeInfo.setProtoType(Type.DOUBLE);
                break;
            case "java.lang.String":
                msgInfo = new MessageInfo();
                msgInfo.setMessageName("StringValue");
                msgInfo.setImpFile("edap-idl/wrappers.proto");
                typeInfo.setMessageInfo(msgInfo);
                typeInfo.setProtoType(Type.STRING);
                break;
            case "[B":
            case "[Ljava.lang.Byte;":
                msgInfo = new MessageInfo();
                msgInfo.setMessageName("BytesValue");
                msgInfo.setImpFile("edap-idl/wrappers.proto");
                msgInfo.setJavaType(javaType);
                typeInfo.setMessageInfo(msgInfo);
                typeInfo.setProtoType(Type.BYTES);
                break;
            default:
                msgInfo = new MessageInfo();
                msgInfo.setMessageName(Type.MESSAGE.name());
                msgInfo.setJavaType(javaType);
                typeInfo.setMessageInfo(msgInfo);
                typeInfo.setProtoType(Type.MESSAGE);
        }
        return typeInfo;
    }

    public static boolean isRepeatedArray(java.lang.reflect.Type type) {
        if (type instanceof Class) {
            Class arrayCls = (Class)type;
            return arrayCls.isArray() && !"[B".equals(arrayCls.getName())
                    && !"[Ljava.lang.Byte;".equals(arrayCls.getName());
        }
        return false;
    }

    public static ProtoTypeInfo javaToProtoType(java.lang.reflect.Type javaType) {
        Type type = Type.BYTES;
        ProtoTypeInfo typeInfo = new ProtoTypeInfo();
        if (javaType.getTypeName().equals("java.lang.Object") || javaType.getTypeName().startsWith("java.lang.Class")
                || javaType instanceof TypeVariable) {
            typeInfo.setProtoType(Type.OBJECT);
            return typeInfo;
        }
        if (AsmUtil.isMap(javaType)) {
            typeInfo.setProtoType(Type.MAP);
            return typeInfo;
        }
        if (AsmUtil.isPojo(javaType)) {
            typeInfo.setProtoType(Type.MESSAGE);
            return typeInfo;
        }
        if (AsmUtil.isArray(javaType)) {
            ProtoTypeInfo innerTypeInfo = javaToProtoType(((Class)javaType).getComponentType());
            typeInfo.setProtoType(innerTypeInfo.getProtoType());
            typeInfo.setCardinality(Cardinality.REPEATED);
            typeInfo.setProtoTypeInfo(innerTypeInfo);
            return typeInfo;
        }
        if (isList(javaType) || isSet(javaType) || isIterable(javaType)) {
            if (javaType instanceof ParameterizedType) {
                ParameterizedType pType = (ParameterizedType)javaType;
                java.lang.reflect.Type[] types = pType.getActualTypeArguments();
                if (types != null && types.length > 0) {
                    java.lang.reflect.Type itemType = types[0];
                    if (itemType instanceof TypeVariable) {
                        typeInfo.setProtoType(Type.OBJECT);
                        typeInfo.setCardinality(Cardinality.REPEATED);
                        return typeInfo;
                    } else if (itemType instanceof Class) {
                        ProtoTypeInfo innerTypeInfo = javaToProtoType((Class)types[0]);
                        typeInfo.setProtoType(innerTypeInfo.getProtoType());
                        typeInfo.setCardinality(Cardinality.REPEATED);
                        typeInfo.setProtoTypeInfo(innerTypeInfo);
                        return typeInfo;
                    }
                }
            } else if (javaType instanceof Class) {
                typeInfo.setCardinality(Cardinality.REPEATED);
                typeInfo.setProtoType(Type.OBJECT);
                return typeInfo;
            }
        }
        if (javaType instanceof ParameterizedType) {
            return typeInfo;
        }
        if (javaType instanceof GenericArrayType) {
            GenericArrayType gat = (GenericArrayType)javaType;
            if (gat.getGenericComponentType() instanceof ParameterizedType) {
                ParameterizedType pType = (ParameterizedType)gat.getGenericComponentType();
                java.lang.reflect.Type[] types = pType.getActualTypeArguments();
                if (pType.getRawType() instanceof Class) {
                    Class cls = (Class)pType.getRawType();
                    return javaToProtoType(cls);
                }
            }
        }
        return javaToProtoType((Class)javaType);
    }

    private static ProtoField buildProtoFieldAnnotation(final int tag,
                                                        final Cardinality cardinality, final Type type,
                                                        final String[] options) {
        ProtoField pf = new ProtoField() {
            @Override
            public Cardinality cardinality() {
                return cardinality;
            }

            @Override
            public int tag() {
                return tag;
            }

            @Override
            public Type type() {
                return type;
            }

            @Override
            public String[] options() {
                return options;
            }

            @Override
            public String comment() {
                return "";
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return null;
            }
        };
        return pf;
    }

    public static String buildMapEncodeName(java.lang.reflect.Type mapType) {
        StringBuilder name = new StringBuilder("io.edap.protobuf.mapencoder.MapEncoder_");
        if (mapType instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)mapType;
            java.lang.reflect.Type[] types = ptype.getActualTypeArguments();
            StringBuilder codes = new StringBuilder();
            for (int i=0;i<types.length;i++) {
                if (i > 0) {
                    codes.append("_");
                }
                codes.append(types[i].getTypeName());
            }
            name.append(md5(codes.toString()));
        } else if (mapType instanceof Class) {
            Class mapClazz = (Class)mapType;
            if (isMap(mapClazz)) {
                name.append(md5("java.lang.Object_java.lang.Object"));
            } else {
                throw  new RuntimeException("mapType [" + mapType.getTypeName() + "] is not map");
            }
        } else {
            throw  new RuntimeException("mapType [" + mapType.getTypeName() + "] is not map");
        }
        return name.toString();
    }

    private static boolean needEncode(Field field) {
        if ("org.slf4j.Logger".equals(field.getType().getName())) {
            return false;
        }
        int mod = field.getModifiers();
        return !Modifier.isStatic(mod) && !Modifier.isTransient(mod);
    }

    public static boolean needEncode(FieldNode fieldNode) {
        if ("Lorg/slf4j/Logger;".equals(fieldNode.desc)) {
            return false;
        }
        int mod = fieldNode.access;
        List<AnnotationNode> fieldAnns = fieldNode.visibleAnnotations;
        if (fieldAnns != null) {
            for (AnnotationNode annNode : fieldAnns) {
                if ("Ljavax/persistence/Transient;".equals(annNode.desc)) {
                    return false;
                }
            }
        }
        return !Modifier.isStatic(mod) && !Modifier.isTransient(mod);
    }

    private static ProtoField getProtoAnnotation(Field field, Method method) {
        Annotation[] anns = field.getAnnotations();
        ProtoField pf = getProtoAnnotation(anns);
        if (pf != null) {
            return pf;
        }
        if (method != null) {
            anns = method.getAnnotations();
            pf = getProtoAnnotation(anns);
        }
        return pf;
    }

    private static ProtoField getProtoAnnotation(Annotation[] anns) {
        for (Annotation ann : anns) {
            if (ann instanceof ProtoField) {
                return (ProtoField) ann;
            }
        }
        return null;
    }

    /**
     * 查找是否有获取private标识field的方法
     * @param f
     * @param aMethod
     * @return
     */
    private static Method getSetMethod(Field f, Map<String, Method> aMethod) {
        String methodName = "set" + upperCaseFirst(f.getName());
        Method m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 1) {
            String fd = getDescriptor(f.getGenericType());
            try {
                String md = getDescriptor(m.getGenericParameterTypes()[0]);
                if (fd.equals(md)) {
                    return m;
                }
            } catch (Throwable t) {
                throw new RuntimeException("getDescriptor error", t);
            }
        }
        methodName = f.getName();
        m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 1) {
            String fd = getDescriptor(f.getGenericType());
            String md = getDescriptor(m.getGenericParameterTypes()[0]);
            if (fd.equals(md)) {
                return m;
            }
        }
        return null;
    }

    /**
     * 查找是否有获取private标识field的方法
     * @param f
     * @param aMethod
     * @return
     */
    private static Method getAccessMethod(Field f, Map<String, Method> aMethod) {
        String methodName = "get" + upperCaseFirst(f.getName());
        Method m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 0) {
            String fd = getDescriptor(f.getGenericType());
            try {
                String md = getDescriptor(m.getGenericReturnType());
                if (fd.equals(md)) {
                    return m;
                }
            } catch (Throwable t) {
                throw new RuntimeException("getDescriptor error", t);
            }
        }
        if (f.getType().getName().equals("java.lang.Boolean")
                || f.getType().getName().equals("boolean")) {
            methodName = "is" + upperCaseFirst(f.getName());
        }
        m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 0) {
            String fd = getDescriptor(f.getGenericType());
            String md = getDescriptor(m.getGenericReturnType());
            if (fd.equals(md)) {
                return m;
            }
        }
        methodName = f.getName();
        m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 0) {
            String fd = getDescriptor(f.getGenericType());
            String md = getDescriptor(m.getGenericReturnType());
            if (fd.equals(md)) {
                return m;
            }
        }
        return null;
    }

    public static List<java.lang.reflect.Type> getAllPojoTypes(
            java.lang.reflect.Type genericType) {
        List<java.lang.reflect.Type> types = new ArrayList<>();
        if (genericType instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)genericType;
            java.lang.reflect.Type[] ts = ptype.getActualTypeArguments();
            if (ts == null || ts.length == 0) {
                return types;
            }
            for (java.lang.reflect.Type t : ts) {
                if (t instanceof ParameterizedType) {
                    types.addAll(getAllPojoTypes(t));
                } else {
                    if (isPojo(t) && !types.contains(t)) {
                        types.add(t);
                    }
                }
            }
        } else {
            return types;
        }
        return types;
    }

    /**
     *
     * @param value
     * @return
     */
    public static int computeRawVarint32Size(final int value){
        if ((value & (0xffffffff << 7)) == 0)
            return 1;
        if ((value & (0xffffffff << 14)) == 0)
            return 2;
        if ((value & (0xffffffff << 21)) == 0)
            return 3;
        if ((value & (0xffffffff << 28)) == 0)
            return 4;
        return 5;
    }

    /**
     * Compute the number of bytes that would be needed to encode a varint.
     * @param value
     * @return
     */
    public static int computeRawVarint64Size(final long value) {
        if ((value & (0xffffffffffffffffL << 7)) == 0)
            return 1;
        if ((value & (0xffffffffffffffffL << 14)) == 0)
            return 2;
        if ((value & (0xffffffffffffffffL << 21)) == 0)
            return 3;
        if ((value & (0xffffffffffffffffL << 28)) == 0)
            return 4;
        if ((value & (0xffffffffffffffffL << 35)) == 0)
            return 5;
        if ((value & (0xffffffffffffffffL << 42)) == 0)
            return 6;
        if ((value & (0xffffffffffffffffL << 49)) == 0)
            return 7;
        if ((value & (0xffffffffffffffffL << 56)) == 0)
            return 8;
        if ((value & (0xffffffffffffffffL << 63)) == 0)
            return 9;
        return 10;
    }
}
